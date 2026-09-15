#!/usr/bin/env python3
"""Rootless sibling-network preflight, not a backend/Testcontainers test runner."""
import argparse
import contextlib
import ipaddress
import json
import os
import shutil
from pathlib import Path
import subprocess
import sys
import uuid

IMAGE = "python@sha256:78387bc3881b8273120a12ebe6c1ab22b018ccc2c9adf565ae1ac9b536e184ea"
ACCOUNT = "feelm-ci-test"
LABEL = "com.feelm.ci222.probe"


class NamespaceError(ValueError):
    """Only fixed diagnostic messages; never contains a command or environment."""

SERVER = '''
import http.server, os
class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200)
        self.end_headers()
        self.wfile.write(os.environ["PROBE_TOKEN"].encode())
    def log_message(self, *args): pass
http.server.HTTPServer(("0.0.0.0", 8000), Handler).serve_forever()
'''

ERROR_HELPER = '''
def error_details(exc):
    reason = getattr(exc, "reason", exc)
    return {"error_type": type(exc).__name__, "reason_type": type(reason).__name__,
            "errno": getattr(reason, "errno", None), "http_status": getattr(exc, "code", None)}
'''

CLIENT = ERROR_HELPER + '''
import json, os, socket, time, urllib.request
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
token = os.environ["PROBE_TOKEN"].encode()
ok = True
paths = [
    ("SIBLING_HTTP", "http://probe-server:8000"),
    ("SIBLING_IP_HTTP", "http://" + os.environ["PROBE_SERVER_IP"] + ":8000"),
    ("MAPPED_PORT_HTTP", "http://" + os.environ["PROBE_GATEWAY"] + ":" + os.environ["PROBE_PORT"]),
]
if os.environ.get("PROBE_MODE") == "namespace":
    paths = [
        ("NAMESPACE_CONTAINER_IP_HTTP", "http://" + os.environ["PROBE_SERVER_IP"] + ":8000"),
        ("NAMESPACE_LOOPBACK_MAPPED_HTTP", "http://127.0.0.1:" + os.environ["PROBE_PORT"]),
    ]
for label, url in paths:
    success = False
    reason = {}
    print("CONNECT_TARGET: " + json.dumps({"check": label, "url": url}), flush=True)
    for attempt in range(5):
        try:
            with opener.open(url, timeout=2) as response:
                success = response.read(128) == token
                if not success:
                    reason = {"error_type": "ResponseTokenMismatch"}
            if success: break
        except Exception as exc:
            reason = error_details(exc)
        time.sleep(0.2)
    print(label + ": " + ("PASS" if success else "FAIL " + json.dumps(reason)), flush=True)
    ok = ok and success
try:
    socket.getaddrinfo("lab.ssafy.com", 443, type=socket.SOCK_STREAM)
    with opener.open("https://lab.ssafy.com", timeout=10) as response:
        success = response.status == 200
    print("GITLAB_DNS_HTTPS: " + ("PASS" if success else "FAIL"), flush=True)
    ok = ok and success
except Exception as exc:
    print("GITLAB_DNS_HTTPS: FAIL " + json.dumps(error_details(exc)), flush=True)
    ok = False
targets = [
    ("backend", "172.26.0.186", 8080),
    ("kafka", "172.26.0.121", 9092),
    ("hdfs", "172.26.0.121", 8020),
    ("spark", "172.26.0.121", 7077),
    ("service-public", os.environ["PROBE_SERVICE_IP"], 443),
    ("distributed-public", os.environ["PROBE_DISTRIBUTED_IP"], 443),
    ("metadata", "169.254.169.254", 80),
]
for label, host, port in targets:
    try:
        with socket.create_connection((host, port), timeout=2): pass
        print(label + ": UNEXPECTED_REACHABLE", flush=True)
        ok = False
    except OSError as exc:
        print(label + ": CONNECTION_FAILED_EVIDENCE_ONLY " + json.dumps(error_details(exc)), flush=True)
print("COUNTERS_AND_IPV6_REVIEW_STILL_REQUIRED", flush=True)
raise SystemExit(0 if ok else 2)
'''

NAMESPACE_SETUP = r'''
import contextlib, ctypes, os, tempfile

def verify_namespace_identity():
    for kind in ("user", "net", "mnt"):
        if os.stat("/proc/self/ns/" + kind).st_ino != int(os.environ["PROBE_NS_" + kind]):
            raise RuntimeError("Namespace identity mismatch")
    with open("/proc/self/uid_map") as stream:
        mapping = [int(x) for x in stream.readline().split()]
    if mapping != [0, int(os.environ["PROBE_OUTER_UID"]), 1] or os.geteuid() != 0:
        raise RuntimeError("Unexpected rootless UID mapping")
    print("NAMESPACE_IDENTITY: PASS (rootless mapped UID, not host root)", flush=True)

def syscall_ok(result, stage):
    if result != 0:
        raise OSError(ctypes.get_errno(), stage)

@contextlib.contextmanager
def private_dns(libc=None):
    if libc is None:
        libc = ctypes.CDLL(None, use_errno=True)
        libc.unshare.argtypes = [ctypes.c_int]
        libc.mount.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_char_p,
                               ctypes.c_ulong, ctypes.c_void_p]
        libc.umount2.argtypes = [ctypes.c_char_p, ctypes.c_int]
        libc.unshare.restype = libc.mount.restype = libc.umount2.restype = ctypes.c_int
    original = os.stat("/proc/self/ns/mnt").st_ino
    syscall_ok(libc.unshare(0x00020000), "DNS_UNSHARE_FAILED")  # CLONE_NEWNS
    if os.stat("/proc/self/ns/mnt").st_ino == original:
        raise RuntimeError("DNS mount namespace did not change")
    # Never install a bind mount until propagation is private in this new namespace.
    syscall_ok(libc.mount(None, b"/", None, (1 << 14) | (1 << 18), None),
               "DNS_PRIVATE_PROPAGATION_FAILED")  # MS_REC | MS_PRIVATE
    target = os.path.realpath("/etc/resolv.conf")
    if not os.path.isfile(target):
        raise RuntimeError("DNS target is not an existing regular file")
    payload = "nameserver 172.26.0.2\noptions timeout:2 attempts:2\n"
    with tempfile.TemporaryDirectory(prefix="ci222-dns-", dir="/tmp") as directory:
        source = directory + "/resolv.conf"
        with open(source, "x", encoding="ascii") as stream:
            stream.write(payload)
        os.chmod(source, 0o600)
        encoded_target = os.fsencode(target)
        syscall_ok(libc.mount(os.fsencode(source), encoded_target, None, 4096, None),
                   "DNS_BIND_FAILED")  # MS_BIND
        try:
            with open("/etc/resolv.conf", encoding="ascii") as stream:
                if stream.read() != payload:
                    raise RuntimeError("DNS mount readback mismatch")
            print("PRIVATE_DNS: 172.26.0.2 (probe-only mount namespace)", flush=True)
            yield
        finally:
            syscall_ok(libc.umount2(encoded_target, 0), "DNS_UNMOUNT_FAILED")
    print("PRIVATE_DNS_CLEANUP: PASS", flush=True)
'''

NAMESPACE_CLIENT = NAMESPACE_SETUP + '''
import resource, sys
try:
    verify_namespace_identity()
    resource.setrlimit(resource.RLIMIT_AS, (512 * 1024 * 1024, 512 * 1024 * 1024))
    resource.setrlimit(resource.RLIMIT_CPU, (30, 30))
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    with private_dns():
        try:
            exec(''' + repr(CLIENT) + ''')
        except SystemExit as result:
            probe_code = result.code
    raise SystemExit(probe_code)
except (OSError, RuntimeError) as exc:
    print("NAMESPACE_DNS_ERROR:", type(exc).__name__, "errno=", getattr(exc, "errno", None),
          "stage=", str(exc), file=sys.stderr)
    raise SystemExit(2)
'''


def public_ip(value):
    address = ipaddress.ip_address(value)
    if address.version != 4 or not address.is_global:
        raise argparse.ArgumentTypeError("Expected an operator-verified public IPv4")
    return str(address)


def docker_prefix():
    import pwd
    import grp
    uid = os.geteuid()
    account = pwd.getpwuid(uid)
    groups = {grp.getgrgid(g).gr_name for g in os.getgrouplist(account.pw_name, account.pw_gid)}
    if uid < 1000 or account.pw_name != ACCOUNT or groups != {ACCOUNT}:
        raise ValueError("Run as feelm-ci-test with no supplemental groups, never root")
    if os.access("/var/run/docker.sock", os.R_OK) or os.access("/var/run/docker.sock", os.W_OK):
        raise ValueError("Operating Docker socket is accessible; stop")
    socket_path = Path(f"/run/user/{uid}/docker.sock")
    if not socket_path.is_socket() or socket_path.stat().st_uid != uid:
        raise ValueError("Missing or unexpected rootless socket owner")
    return ["docker", "--host", f"unix://{socket_path}"]


def execute(prefix, *args, timeout=30, check=True):
    return subprocess.run(prefix + list(args), text=True, capture_output=True,
                          timeout=timeout, check=check)


def limits():
    return ["--read-only", "--user", "65534:65534", "--cap-drop", "ALL",
            "--security-opt", "no-new-privileges", "--memory", "256m",
            "--cpus", "1", "--pids-limit", "64", "--log-driver", "none"]


def cleanup(prefix, created):
    failed = []
    for kind, resource_id in reversed(created):
        args = ("rm", "-f", resource_id) if kind == "container" else ("network", "rm", resource_id)
        try:
            execute(prefix, *args)
        except (OSError, subprocess.SubprocessError):
            failed.append((kind, resource_id))
    if failed:
        print("CLEANUP_FAILED: " + json.dumps(failed), file=sys.stderr)
    return not failed


def route_details(container, network):
    bindings = container["NetworkSettings"]["Ports"]["8000/tcp"]
    if not bindings or len(bindings) != 1 or bindings[0]["HostIp"] != "127.0.0.1":
        raise ValueError("Unexpected non-loopback publishing")
    port = int(bindings[0]["HostPort"])
    if not 1 <= port <= 65535:
        raise ValueError("Invalid mapped port")
    endpoints = [v for v in container["NetworkSettings"]["Networks"].values()
                 if v.get("NetworkID") == network["Id"]]
    if len(endpoints) != 1:
        raise ValueError("Unexpected server network attachment")
    server_ip = str(ipaddress.IPv4Address(endpoints[0]["IPAddress"]))
    gateway = str(ipaddress.IPv4Address(endpoints[0]["Gateway"]))
    return {"published_host_ip": "127.0.0.1", "published_host_port": port,
            "server_container_ip": server_ip, "server_container_port": 8000,
            "client_mapped_target_ip": gateway,
            "binding_target_match": gateway == "127.0.0.1",
            "note": "Loopback binding and bridge gateway differ; not proof of firewall failure"}


def validate_namespace_process(uid, owner, comm, mapping, namespaces, current):
    if owner != uid or comm.strip() != "dockerd":
        raise NamespaceError("Namespace target is not this user's dockerd")
    if not mapping.splitlines() or mapping.splitlines()[0].split() != ["0", str(uid), "1"]:
        raise NamespaceError("Unexpected rootless UID map")
    if any(namespaces[kind] == current[kind] for kind in ("user", "net", "mnt")):
        raise NamespaceError("Target must have separate user, network and mount namespaces")


@contextlib.contextmanager
def namespace_target(*, offline=False):
    """Pin verified namespace FDs; never enter namespaces selected by arbitrary CLI PID."""
    import fcntl
    uid = os.geteuid()
    pid_file = Path('/run/feelm-ci222-offline/docker.pid' if offline
                    else f"/run/user/{uid}/docker.pid")
    if pid_file.is_symlink() or pid_file.stat().st_uid != uid:
        raise NamespaceError("Unexpected Docker PID file owner")
    pid = int(pid_file.read_text().strip())
    if pid <= 1:
        raise NamespaceError("Invalid Docker PID")
    proc = Path(f"/proc/{pid}")
    paths = {"user": proc / "ns/user",
             "net": (proc / 'ns/net' if offline else
                     proc / "root" / f"run/user/{uid}/dockerd-rootless/netns"),
             "mnt": proc / "ns/mnt"}
    expected_types = {"user": 0x10000000, "net": 0x40000000, "mnt": 0x00020000}
    fds = {}
    try:
        for kind in ("user", "net", "mnt"):
            fds[kind] = os.open(paths[kind], os.O_RDONLY)
            try:
                actual_type = fcntl.ioctl(fds[kind], 0xb703)  # Linux NS_GET_NSTYPE
            except OSError as exc:
                raise NamespaceError("Target is not an accessible namespace object") from exc
            if actual_type != expected_types[kind]:
                raise NamespaceError("Unexpected namespace object type")
        identities = {kind: os.fstat(fd).st_ino for kind, fd in fds.items()}
        current = {kind: os.stat(f"/proc/self/ns/{kind}").st_ino for kind in fds}
        validate_namespace_process(uid, proc.stat().st_uid, (proc / "comm").read_text(),
                                   (proc / "uid_map").read_text(), identities, current)
        # Detect target replacement during inspection. Open FDs also avoid PID reuse at exec.
        if any(os.stat(paths[kind]).st_ino != identities[kind] for kind in fds):
            raise NamespaceError("Namespace target changed during inspection")
        # The detached network must belong to the verified rootless user namespace.
        owner_fd = fcntl.ioctl(fds["net"], 0xb701)  # Linux NS_GET_USERNS
        try:
            if os.fstat(owner_fd).st_ino != identities["user"]:
                raise NamespaceError("Detached network belongs to another user namespace")
        finally:
            os.close(owner_fd)
        nsenter = shutil.which("nsenter", path="/usr/bin:/bin")
        if not nsenter:
            raise NamespaceError("nsenter is required; no automatic installation")
        command = [nsenter, "--preserve-credentials"]
        for option, kind in (("user", "user"), ("net", "net"), ("mount", "mnt")):
            command.append(f"--{option}=/proc/self/fd/{fds[kind]}")
        print("NAMESPACE_TARGET: " + ("verified offline Docker direct network" if offline else
              "verified detached network from Docker mount view"), flush=True)
        yield command, fds, identities, uid
    finally:
        for fd in fds.values():
            os.close(fd)


def run_namespace_client(target, route, token, service_ip, distributed_ip):
    command, fds, identities, uid = target
    # Deliberately no inherited credentials, proxies, arbitrary command or shell.
    env = {"PATH": "/usr/bin:/bin", "LANG": "C.UTF-8", "PROBE_MODE": "namespace",
           "PROBE_TOKEN": token, "PROBE_PORT": str(route["published_host_port"]),
           "PROBE_GATEWAY": route["client_mapped_target_ip"],
           "PROBE_SERVER_IP": route["server_container_ip"], "PROBE_OUTER_UID": str(uid),
           "PROBE_SERVICE_IP": service_ip, "PROBE_DISTRIBUTED_IP": distributed_ip}
    env.update({"PROBE_NS_" + kind: str(value) for kind, value in identities.items()})
    return subprocess.run(command + ["--", "/usr/bin/python3", "-I", "-B", "-u", "-"],
                          input=NAMESPACE_CLIENT, env=env, cwd="/", pass_fds=tuple(fds.values()),
                          text=True, capture_output=True, timeout=90, check=False)


def probe(prefix, service_ip, distributed_ip, namespace=None):
    info = json.loads(execute(prefix, "info", "--format", "{{json .}}").stdout)
    if "name=rootless" not in info.get("SecurityOptions", []):
        raise ValueError("Not a rootless daemon")
    if info.get("DockerRootDir") != f"/home/{ACCOUNT}/.local/share/docker":
        raise ValueError("Unexpected Docker data directory")
    # No implicit image pull or credentials forwarded into containers.
    execute(prefix, "image", "inspect", IMAGE)
    token = uuid.uuid4().hex
    name = "ci222-probe-" + token
    created = []
    code = 2
    try:
        network = execute(prefix, "network", "create", "--driver", "bridge",
                          "--label", f"{LABEL}={token}", name).stdout.strip()
        created.append(("network", network))
        server = execute(prefix, "create", "--name", name + "-server",
                         "--label", f"{LABEL}={token}", "--network", network,
                         "--network-alias", "probe-server", *limits(),
                         "--publish", "127.0.0.1::8000", "--env", f"PROBE_TOKEN={token}",
                         IMAGE, "python", "-B", "-c", SERVER).stdout.strip()
        created.append(("container", server))
        execute(prefix, "start", server)
        details = json.loads(execute(prefix, "inspect", server).stdout)[0]
        network_info = json.loads(execute(prefix, "network", "inspect", network).stdout)[0]
        route = route_details(details, network_info)
        print("ROUTE_DETAILS: " + json.dumps(route), flush=True)
        port = str(route["published_host_port"])
        gateway = route["client_mapped_target_ip"]
        if namespace is not None:
            result = run_namespace_client(namespace, route, token, service_ip, distributed_ip)
            print(result.stdout, end="")
            if result.stderr:
                print(result.stderr, file=sys.stderr, end="")
            code = 0 if result.returncode == 0 else 2
            # This is a different execution path, not a pass for sibling mapped-port access.
            print("NAMESPACE_PROBE_ONLY: no MR code or backend tests executed")
        else:
            code = run_sibling_client(prefix, created, name, network, token, gateway, route,
                                      port, service_ip, distributed_ip)
    finally:
        if not cleanup(prefix, created):
            code = 3
    return code


def run_sibling_client(prefix, created, name, network, token, gateway, route,
                       port, service_ip, distributed_ip):
    client = execute(prefix, "create", "--name", name + "-client",
                         "--label", f"{LABEL}={token}", "--network", network, *limits(),
                         "--env", f"PROBE_TOKEN={token}", "--env", f"PROBE_GATEWAY={gateway}",
                         "--env", f"PROBE_SERVER_IP={route['server_container_ip']}",
                         "--env", f"PROBE_PORT={port}", "--env", f"PROBE_SERVICE_IP={service_ip}",
                         "--env", f"PROBE_DISTRIBUTED_IP={distributed_ip}",
                         IMAGE, "python", "-B", "-u", "-c", CLIENT).stdout.strip()
    created.append(("container", client))
    result = execute(prefix, "start", "--attach", client, timeout=90, check=False)
    print(result.stdout, end="")
    if result.stderr:
        print(result.stderr, file=sys.stderr, end="")
    state = json.loads(execute(prefix, "inspect", client, "--format", "{{json .State}}").stdout)
    if (result.returncode == 0 and state.get("ExitCode") == 0
            and state.get("Status") == "exited" and not state.get("OOMKilled")):
        return 0
    return 2


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["plan", "run"], nargs="?", default="plan")
    parser.add_argument("--service-public-ip", type=public_ip)
    parser.add_argument("--distributed-public-ip", type=public_ip)
    parser.add_argument("--confirm", action="store_true")
    parser.add_argument("--client-mode", choices=["sibling", "namespace"], default="sibling")
    args = parser.parse_args()
    if args.action == "plan":
        print("PLAN: client-mode=" + args.client_mode + "; rootless-only; 1 temporary bridge; "
              "sibling: 2 containers; namespace: 1 container and fixed nsenter probe; loopback-only random port; "
              "no mounts/host network/privileged mode; cleanup by created IDs; no backend tests")
        return 0
    if not args.confirm or not args.service_public_ip or not args.distributed_public_ip:
        parser.error("run requires --confirm and both operator-verified public IPs; guard must be active")
    prefix = docker_prefix()
    if args.client_mode == "namespace":
        with namespace_target() as target:
            result = probe(prefix, args.service_public_ip, args.distributed_public_ip, target)
    else:
        result = probe(prefix, args.service_public_ip, args.distributed_public_ip)
    print("PREFLIGHT_EXIT=" + str(result) + "; NOT_RUNNER_ISOLATION_APPROVAL")
    return result


if __name__ == "__main__":
    try:
        sys.exit(main())
    except NamespaceError as exc:
        print("NAMESPACE_REJECTED: " + str(exc), file=sys.stderr)
        sys.exit(2)
    except (ValueError, KeyError, IndexError, OSError, subprocess.SubprocessError) as exc:
        # CalledProcessError includes command arguments: avoid emitting the full command.
        print("PREFLIGHT_ERROR: " + type(exc).__name__, file=sys.stderr)
        sys.exit(2)
