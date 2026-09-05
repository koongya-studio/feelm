"""Static safety regression checks; no WSL or host resource changes."""
import importlib.util
from pathlib import Path
import unittest


SOURCE = Path(__file__).with_name('ci-local-manager-check.py')
spec = importlib.util.spec_from_file_location('local_manager', SOURCE)
manager = importlib.util.module_from_spec(spec)
spec.loader.exec_module(manager)


class ManagerSafetyTests(unittest.TestCase):
    def test_parent_is_emptied_before_controllers_and_daemon(self):
        script = manager.SCRIPT
        move = script.index('echo $$ > /sys/fs/cgroup/manager/cgroup.procs')
        enable = script.index("echo '+cpu +memory +pids'")
        self.assertLess(move, enable)
        self.assertLess(enable, script.index('dockerd --data-root'))
        self.assertIn('test -z "$(cat /sys/fs/cgroup/cgroup.procs)"', script)

    def test_namespace_and_mapping_checks_precede_cgroup_writes(self):
        script = manager.SCRIPT
        first_write = script.index('mkdir /sys/fs/cgroup/manager')
        self.assertLess(script.index('test "$outer_uid" -gt 0'), first_write)
        self.assertLess(script.index('!= "$HOST_CGROUP_NAMESPACE"'), first_write)

    def test_diagnostic_exits_before_mutation(self):
        script = manager.SCRIPT
        self.assertLess(script.index('exit 0'), script.index('mkdir /sys/fs/cgroup/manager'))

    def test_resource_limits_remain_asserted(self):
        for expected in ('--memory 64m', '--cpus 0.5', '--pids-limit 32',
                         '= 67108864', '= "50000 100000"', '= 32'):
            self.assertIn(expected, manager.SCRIPT)

    def test_path_probe_fails_on_any_assertion(self):
        self.assertIn('ci222-local-probe /bin/busybox sh -ec', manager.SCRIPT)

    def test_only_approved_keyctl_exception_and_no_host_cgroup_bind(self):
        text = SOURCE.read_text()
        self.assertEqual(text.count('--system-call-filter='), 1)
        self.assertIn("'--system-call-filter=keyctl'", text)
        self.assertNotIn('SYSTEMD_SECCOMP=0', text)
        self.assertNotIn('--bind=/sys/fs/cgroup', text)
        self.assertIn("'--private-users=pick'", text)
        self.assertIn("'--private-network'", text)

    def test_negative_bind_requires_specific_error(self):
        self.assertIn('test "$reject_exit" = 125', manager.SCRIPT)
        self.assertIn('bind source path does not exist: $source', manager.SCRIPT)
        self.assertIn('for source in "$HOST_CANARY" "$HOST_SOCKET"', manager.SCRIPT)

    def test_privileged_probe_checks_actual_host_namespaces(self):
        self.assertIn('--privileged --pid host --network host --cgroupns host', manager.SCRIPT)
        for kind in ('PID', 'NET', 'CGROUP'):
            self.assertIn('!= "$HOST_' + kind + '_NAMESPACE"', manager.SCRIPT)
        self.assertIn('/proc/1/root$HOST_CANARY', manager.SCRIPT)

    def test_route_probe_does_not_depend_on_busybox_filter_or_tmp(self):
        self.assertNotIn('route show default', manager.SCRIPT)
        self.assertNotIn('/tmp/routes', manager.SCRIPT)
        self.assertIn('default|0.0.0.0/0|::/0) exit 1', manager.SCRIPT)
        self.assertIn('ipv6_routes=$(/bin/busybox ip -6 route show)', manager.SCRIPT)

    def test_lifecycle_requires_ready_workload_and_removed_scope(self):
        source = SOURCE.read_text()
        self.assertIn("while not (root / 'lifecycle-ready').exists()", source)
        self.assertIn("if scope_path.exists(): raise RuntimeError", source)
        self.assertIn("parser.error('Stop fixture must run independently", source)

    def test_backend_uses_separate_reviewed_payload_and_deadline(self):
        source = SOURCE.read_text()
        self.assertIn('backend.inputs.inspect_bundle', source)
        self.assertIn('timeout=1900 if backend else 90', source)
        self.assertIn('backend.report(root, result.returncode)', source)


if __name__ == '__main__':
    unittest.main()
