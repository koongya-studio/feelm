import argparse
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import unittest
import urllib.error
from unittest.mock import Mock, patch, mock_open

spec = importlib.util.spec_from_file_location("probe", Path(__file__).with_name("ci-rootless-network-check.py"))
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


class ProbeTests(unittest.TestCase):
    def test_plan_makes_no_docker_calls(self):
        with patch.object(probe.sys, "argv", ["probe"]), patch.object(probe, "execute") as call:
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(probe.main(), 0)
            call.assert_not_called()

    def test_run_requires_confirmation_and_ips(self):
        with patch.object(probe.sys, "argv", ["probe", "run"]), patch.object(probe, "execute") as call:
            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                probe.main()
            call.assert_not_called()

    def test_public_ip_validation(self):
        self.assertEqual(probe.public_ip("8.8.8.8"), "8.8.8.8")
        for value in ("172.26.0.121", "127.0.0.1", "169.254.169.254", "::1", "example.com"):
            with self.subTest(value=value), self.assertRaises((ValueError, argparse.ArgumentTypeError)):
                probe.public_ip(value)

    def run_mocked(self, *, rootless=True, public_bind=False, client_exit=0, cleanup_error=False,
                   namespace=None):
        calls = []

        def execute(prefix, *args, **kwargs):
            calls.append(args)
            if args[0] == "info":
                result = {"SecurityOptions": ["name=rootless"] if rootless else [],
                          "DockerRootDir": "/home/feelm-ci-test/.local/share/docker"}
            elif args[:2] == ("image", "inspect"):
                result = []
            elif args[:2] == ("network", "create"):
                return Mock(stdout="network-id\n", stderr="", returncode=0)
            elif args[0] == "create":
                return Mock(stdout="server-id" if "--publish" in args else "client-id", stderr="", returncode=0)
            elif args[:2] == ("inspect", "server-id"):
                result = [{"NetworkSettings": {"Ports": {"8000/tcp": [
                    {"HostIp": "0.0.0.0" if public_bind else "127.0.0.1", "HostPort": "49152"}
                ]}, "Networks": {"test-net": {"NetworkID": "network-id",
                    "IPAddress": "172.18.0.2", "Gateway": "172.18.0.1"}}}}]
            elif args[:2] == ("network", "inspect"):
                result = [{"Id": "network-id", "IPAM": {"Config": [{"Gateway": "172.18.0.1"}]}}]
            elif args[:2] == ("inspect", "client-id"):
                result = {"Status": "exited", "ExitCode": client_exit, "OOMKilled": False}
            elif args[:2] == ("rm", "-f") and cleanup_error:
                raise subprocess.CalledProcessError(1, ["docker"])
            else:
                return Mock(stdout="", stderr="", returncode=0)
            return Mock(stdout=json.dumps(result), stderr="", returncode=0)

        self.calls = calls
        with patch.object(probe, "execute", side_effect=execute):
            with contextlib.redirect_stdout(io.StringIO()) as output, contextlib.redirect_stderr(io.StringIO()):
                try:
                    return probe.probe(["docker", "--host", "unix:///run/user/1001/docker.sock"],
                                       "8.8.8.8", "1.1.1.1", namespace)
                finally:
                    self.output = output.getvalue()

    def test_rootful_daemon_rejected_before_creation(self):
        with self.assertRaisesRegex(ValueError, "rootless"):
            self.run_mocked(rootless=False)
        self.assertEqual(len(self.calls), 1)

    def test_no_host_mounts_or_host_network(self):
        self.assertEqual(self.run_mocked(), 0)
        creates = [call for call in self.calls if call[0] == "create"]
        self.assertEqual(len(creates), 2)
        for call in creates:
            self.assertNotIn("--mount", call)
            self.assertNotIn("--volume", call)
            self.assertNotIn("--privileged", call)
            self.assertEqual(call[call.index("--network") + 1], "network-id")
            self.assertIn("no-new-privileges", call)
        self.assertIn("127.0.0.1::8000", creates[0])
        self.assertNotIn("--publish", creates[1])

    def test_cleanup_only_created_ids_reverse_order(self):
        self.run_mocked()
        self.assertEqual(self.calls[-3:], [
            ("rm", "-f", "client-id"), ("rm", "-f", "server-id"), ("network", "rm", "network-id")])

    def test_public_binding_aborts_and_cleans_up(self):
        with self.assertRaisesRegex(ValueError, "non-loopback"):
            self.run_mocked(public_bind=True)
        self.assertEqual(self.calls[-2:], [("rm", "-f", "server-id"), ("network", "rm", "network-id")])

    def test_failed_client_is_not_success(self):
        self.assertEqual(self.run_mocked(client_exit=2), 2)
        self.assertEqual(self.calls[-1], ("network", "rm", "network-id"))

    def test_cleanup_failure_changes_exit_code(self):
        self.assertEqual(self.run_mocked(cleanup_error=True), 3)
        self.assertEqual(self.calls[-1], ("network", "rm", "network-id"))

    def test_embedded_programs_compile(self):
        compile(probe.SERVER, "server", "exec")
        compile(probe.CLIENT, "client", "exec")
        compile(probe.NAMESPACE_CLIENT, "namespace-client", "exec")

    def test_route_reports_binding_mismatch_without_rewriting(self):
        self.run_mocked(client_exit=2)
        route_line = next(line for line in self.output.splitlines() if line.startswith("ROUTE_DETAILS: "))
        route = json.loads(route_line.removeprefix("ROUTE_DETAILS: "))
        self.assertEqual(route["published_host_ip"], "127.0.0.1")
        self.assertEqual(route["client_mapped_target_ip"], "172.18.0.1")
        self.assertEqual(route["server_container_ip"], "172.18.0.2")
        self.assertEqual(route["published_host_port"], 49152)
        self.assertFalse(route["binding_target_match"])
        client = next(c for c in self.calls if c[0] == "create" and "--publish" not in c)
        self.assertIn("PROBE_SERVER_IP=172.18.0.2", client)

    def test_nested_connection_refusal_preserves_errno_not_secret(self):
        scope = {}
        exec(probe.ERROR_HELPER, scope)
        detail = scope["error_details"](urllib.error.URLError(ConnectionRefusedError(111, "SECRET")))
        self.assertEqual(detail["reason_type"], "ConnectionRefusedError")
        self.assertEqual(detail["errno"], 111)
        self.assertNotIn("SECRET", json.dumps(detail))

    def test_timeout_and_http_error_are_distinct(self):
        scope = {}
        exec(probe.ERROR_HELPER, scope)
        timeout = scope["error_details"](urllib.error.URLError(TimeoutError()))
        http = scope["error_details"](urllib.error.HTTPError("SECRET", 403, "SECRET", {}, None))
        self.assertEqual(timeout["reason_type"], "TimeoutError")
        self.assertEqual(http["http_status"], 403)
        self.assertNotIn("SECRET", json.dumps(http))

    def test_namespace_target_rejects_host_or_wrong_owner(self):
        ns = {"user": 11, "net": 12, "mnt": 13}
        host = {"user": 1, "net": 2, "mnt": 3}
        probe.validate_namespace_process(1001, 1001, "dockerd\n", "0 1001 1\n", ns, host)
        for owner, comm, mapping, current in [
            (0, "dockerd", "0 1001 1", host),
            (1001, "other", "0 1001 1", host),
            (1001, "dockerd", "0 0 4294967295", host),
            (1001, "dockerd", "", host),
            (1001, "dockerd", "0 1001 1", dict(host, net=12)),
        ]:
            with self.subTest(owner=owner, comm=comm, mapping=mapping), self.assertRaises(ValueError):
                probe.validate_namespace_process(1001, owner, comm, mapping, ns, current)

    def test_namespace_client_scrubs_environment_and_uses_only_fixed_program(self):
        target = (["/usr/bin/nsenter", "--preserve-credentials"],
                  {"user": 10, "net": 11, "mnt": 12}, {"user": 21, "net": 22, "mnt": 23}, 1001)
        route = {"published_host_port": 32769, "server_container_ip": "172.18.0.2",
                 "client_mapped_target_ip": "172.18.0.1"}
        with patch.dict(probe.os.environ, {"SECRET": "do-not-copy"}), patch.object(probe.subprocess, "run") as run:
            probe.run_namespace_client(target, route, "probe-token", "8.8.8.8", "1.1.1.1")
        args, kwargs = run.call_args
        self.assertEqual(args[0][-6:], ["--", "/usr/bin/python3", "-I", "-B", "-u", "-"])
        self.assertNotIn("SECRET", kwargs["env"])
        self.assertEqual(kwargs["input"], probe.NAMESPACE_CLIENT)
        self.assertEqual(kwargs["pass_fds"], (10, 11, 12))
        self.assertEqual(kwargs["env"]["PROBE_MODE"], "namespace")
        self.assertEqual(kwargs["timeout"], 90)

    def test_namespace_success_only_creates_one_container(self):
        with patch.object(probe, "run_namespace_client", return_value=Mock(returncode=0, stdout="", stderr="")):
            self.assertEqual(self.run_mocked(namespace=object()), 0)
        self.assertEqual(len([c for c in self.calls if c[0] == "create"]), 1)
        self.assertEqual(self.calls[-2:], [("rm", "-f", "server-id"), ("network", "rm", "network-id")])

    def test_namespace_failure_and_timeout_clean_up(self):
        with patch.object(probe, "run_namespace_client", return_value=Mock(returncode=2, stdout="", stderr="")):
            self.assertEqual(self.run_mocked(namespace=object()), 2)
        with patch.object(probe, "run_namespace_client", side_effect=subprocess.TimeoutExpired("nsenter", 90)):
            with self.assertRaises(subprocess.TimeoutExpired):
                self.run_mocked(namespace=object())
        self.assertEqual(self.calls[-2:], [("rm", "-f", "server-id"), ("network", "rm", "network-id")])

    def test_namespace_plan_does_not_enter_namespace(self):
        with patch.object(probe.sys, "argv", ["probe", "plan", "--client-mode", "namespace"]), \
                patch.object(probe, "namespace_target") as target, contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(probe.main(), 0)
        target.assert_not_called()

    def namespace_mock(self, *, network_type=0x40000000, owner_inode=21):
        stack = contextlib.ExitStack()
        self.addCleanup(stack.close)
        stack.enter_context(patch.object(probe.os, "geteuid", return_value=1001, create=True))
        stack.enter_context(patch.object(Path, "is_symlink", return_value=False))
        stack.enter_context(patch.object(Path, "stat", return_value=Mock(st_uid=1001)))
        stack.enter_context(patch.object(Path, "read_text", side_effect=["123", "dockerd", "0 1001 1\n"]))
        opened = stack.enter_context(patch.object(probe.os, "open", side_effect=[40, 41, 42]))
        closed = stack.enter_context(patch.object(probe.os, "close"))
        stack.enter_context(patch.object(probe.os, "fstat", side_effect=lambda fd:
            Mock(st_ino={40: 21, 41: 22, 42: 23, 43: owner_inode}[fd])))
        stack.enter_context(patch.object(probe.os, "stat", side_effect=[
            Mock(st_ino=x) for x in (1, 2, 3, 21, 22, 23)]))
        ioctl = Mock(side_effect=[0x10000000, network_type, 0x00020000, 43])
        stack.enter_context(patch.dict(probe.sys.modules, {"fcntl": Mock(ioctl=ioctl)}))
        stack.enter_context(patch.object(probe.shutil, "which", return_value="/usr/bin/nsenter"))
        return opened, closed

    def test_namespace_opens_detached_network_in_docker_mount_view(self):
        opened, closed = self.namespace_mock()
        with contextlib.redirect_stdout(io.StringIO()), probe.namespace_target() as target:
            self.assertEqual(target[2], {"user": 21, "net": 22, "mnt": 23})
            self.assertIn("--net=/proc/self/fd/41", target[0])
        self.assertEqual(opened.call_args_list[1].args[0].as_posix(),
                         "/proc/123/root/run/user/1001/dockerd-rootless/netns")
        self.assertEqual([c.args[0] for c in closed.call_args_list], [43, 40, 41, 42])

    def test_namespace_rejects_wrong_object_type_and_closes_fds(self):
        _, closed = self.namespace_mock(network_type=0x00020000)
        with self.assertRaisesRegex(probe.NamespaceError, "object type"), probe.namespace_target():
            self.fail("Must not enter")
        self.assertEqual([c.args[0] for c in closed.call_args_list], [40, 41])

    def test_namespace_rejects_other_user_namespace_owner(self):
        _, closed = self.namespace_mock(owner_inode=999)
        with self.assertRaisesRegex(probe.NamespaceError, "another user"), probe.namespace_target():
            self.fail("Must not enter")
        self.assertEqual([c.args[0] for c in closed.call_args_list], [43, 40, 41, 42])

    def test_namespace_rejects_plain_file_without_fallback(self):
        opened, closed = self.namespace_mock(network_type=OSError(25, "Not a namespace"))
        with self.assertRaisesRegex(probe.NamespaceError, "namespace object"), probe.namespace_target():
            self.fail("Must not enter")
        self.assertEqual(opened.call_count, 2)
        self.assertEqual([c.args[0] for c in closed.call_args_list], [40, 41])

    def test_namespace_embedded_client_uses_inner_loopback_and_keeps_denial_checks(self):
        env = {"PROBE_MODE": "namespace", "PROBE_OUTER_UID": "1001",
               "PROBE_NS_user": "21", "PROBE_NS_net": "22", "PROBE_NS_mnt": "23",
               "PROBE_TOKEN": "token", "PROBE_PORT": "32769", "PROBE_GATEWAY": "172.18.0.1",
               "PROBE_SERVER_IP": "172.18.0.2", "PROBE_SERVICE_IP": "8.8.8.8",
               "PROBE_DISTRIBUTED_IP": "1.1.1.1"}
        response = Mock(status=200)
        response.read.return_value = b"token"
        response_context = Mock()
        response_context.__enter__ = Mock(return_value=response)
        response_context.__exit__ = Mock(return_value=False)
        opener = Mock()
        opener.open.return_value = response_context
        with patch.dict(probe.os.environ, env, clear=True), \
                patch.dict(probe.sys.modules, {"resource": Mock()}), \
                patch.object(probe.os, "geteuid", return_value=0, create=True), \
                patch.object(probe.os, "stat", side_effect=[Mock(st_ino=x) for x in (21, 22, 23)]), \
                patch("builtins.open", mock_open(read_data="0 1001 1\n")), \
                patch("urllib.request.build_opener", return_value=opener), \
                patch("socket.getaddrinfo"), \
                patch("socket.create_connection", side_effect=TimeoutError()) as connect, \
                contextlib.redirect_stdout(io.StringIO()) as output, self.assertRaises(SystemExit) as exit_result:
            exec(probe.CLIENT, {})
        self.assertEqual(exit_result.exception.code, 0)
        urls = [call.args[0] for call in opener.open.call_args_list]
        self.assertEqual(urls, ["http://172.18.0.2:8000", "http://127.0.0.1:32769", "https://lab.ssafy.com"])
        self.assertEqual(connect.call_count, 7)
        self.assertIn("NAMESPACE_LOOPBACK_MAPPED_HTTP: PASS", output.getvalue())

    def dns_mock(self, *, unshare_result=0, mount_results=(0, 0), unmount_result=0):
        scope = {}
        exec(probe.NAMESPACE_SETUP, scope)
        stack = contextlib.ExitStack()
        self.addCleanup(stack.close)
        stack.enter_context(patch.object(probe.os, "stat", side_effect=[Mock(st_ino=1), Mock(st_ino=2)]))
        stack.enter_context(patch.object(probe.os.path, "realpath", return_value="/run/systemd/resolve/stub-resolv.conf"))
        stack.enter_context(patch.object(probe.os.path, "isfile", return_value=True))
        chmod = stack.enter_context(patch.object(probe.os, "chmod"))
        directory = stack.enter_context(patch("tempfile.TemporaryDirectory"))
        directory.return_value.__enter__.return_value = "/tmp/ci222-dns-test"
        opened = stack.enter_context(patch("builtins.open", mock_open(
            read_data="nameserver 172.26.0.2\noptions timeout:2 attempts:2\n")))
        libc = Mock()
        libc.unshare.return_value = unshare_result
        libc.mount.side_effect = mount_results
        libc.umount2.return_value = unmount_result
        return scope, libc, opened, directory, chmod

    def test_dns_private_namespace_precedes_bind_and_never_writes_original(self):
        scope, libc, opened, directory, _ = self.dns_mock()
        with contextlib.redirect_stdout(io.StringIO()), scope["private_dns"](libc):
            pass
        self.assertEqual(libc.mock_calls[:3], [
            unittest.mock.call.unshare(0x00020000),
            unittest.mock.call.mount(None, b"/", None, (1 << 14) | (1 << 18), None),
            unittest.mock.call.mount(b"/tmp/ci222-dns-test/resolv.conf",
                                    b"/run/systemd/resolve/stub-resolv.conf", None, 4096, None)])
        self.assertEqual(opened.call_args_list[0].args, ("/tmp/ci222-dns-test/resolv.conf", "x"))
        self.assertEqual(opened.call_args_list[1].args, ("/etc/resolv.conf",))
        libc.umount2.assert_called_once_with(b"/run/systemd/resolve/stub-resolv.conf", 0)
        directory.return_value.__exit__.assert_called_once()

    def test_dns_unshare_failure_does_not_mount_or_create_file(self):
        scope, libc, opened, directory, _ = self.dns_mock(unshare_result=-1)
        with self.assertRaises(OSError), scope["private_dns"](libc):
            self.fail("Must not run probe")
        libc.mount.assert_not_called()
        opened.assert_not_called()
        directory.assert_not_called()

    def test_dns_propagation_failure_does_not_bind_or_create_file(self):
        scope, libc, opened, directory, _ = self.dns_mock(mount_results=(-1,))
        with self.assertRaises(OSError), scope["private_dns"](libc):
            self.fail("Must not run probe")
        self.assertEqual(libc.mount.call_count, 1)
        opened.assert_not_called()
        directory.assert_not_called()

    def test_dns_probe_exception_still_unmounts(self):
        scope, libc, _, directory, _ = self.dns_mock()
        with contextlib.redirect_stdout(io.StringIO()), self.assertRaises(RuntimeError):
            with scope["private_dns"](libc):
                raise RuntimeError("probe failure")
        libc.umount2.assert_called_once()
        directory.return_value.__exit__.assert_called_once()

    def test_dns_unmount_failure_is_not_success(self):
        scope, libc, _, _, _ = self.dns_mock(unmount_result=-1)
        with contextlib.redirect_stdout(io.StringIO()), self.assertRaises(OSError):
            with scope["private_dns"](libc):
                pass

    def test_dns_bind_failure_cleans_temp_without_running_probe(self):
        scope, libc, _, directory, _ = self.dns_mock(mount_results=(0, -1))
        with self.assertRaises(OSError), scope["private_dns"](libc):
            self.fail("Must not run probe")
        libc.umount2.assert_not_called()
        directory.return_value.__exit__.assert_called_once()

    def test_dns_readback_mismatch_unmounts_and_rejects(self):
        scope, libc, opened, _, _ = self.dns_mock()
        opened.return_value.read.return_value = "nameserver 127.0.0.53\n"
        with self.assertRaisesRegex(RuntimeError, "readback mismatch"), scope["private_dns"](libc):
            self.fail("Must not run probe")
        libc.umount2.assert_called_once()


if __name__ == "__main__":
    unittest.main()
