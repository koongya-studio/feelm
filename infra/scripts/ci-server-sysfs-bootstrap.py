#!/usr/bin/env python3
"""Fixed pre-workload bootstrap: fresh readonly sysfs in verified pilot namespaces.

Never bind host /sys or /proc. Never enter a supplied/untrusted MR namespace.
Only the waiting PID-1 BusyBox of a root-owned, reviewed pilot is accepted.
"""
import argparse
import ctypes
import json
import os
from pathlib import Path
import re
import stat

CLONE_NEWNS = 0x00020000
CLONE_NEWNET = 0x40000000
MOUNT_FLAGS = 1 | 2 | 4 | 8  # MOUNT_ATTR_RDONLY, NOSUID, NODEV, NOEXEC
DESTINATION = '/run/ci222-sysfs'


def mapped_uid(text):
    entries = [line.split() for line in text.splitlines() if line.strip()]
    if len(entries) != 1 or len(entries[0]) != 3:
        raise ValueError('Expected one user-namespace mapping')
    inner, outer, count = map(int, entries[0])
    if inner != 0 or outer < 65536 or count != 65536 or outer % 65536:
        raise ValueError('Unexpected mapped pilot UID range')
    return outer


def mount_record(text):
    for line in text.splitlines():
        fields = line.split()
        if len(fields) > 8 and fields[4] == DESTINATION:
            split = fields.index('-')
            if fields[split + 1] != 'sysfs' or fields[3] != '/':
                raise ValueError('Expected a new full sysfs, not a subtree bind')
            if not {'ro', 'nosuid', 'nodev', 'noexec'} <= set(fields[5].split(',')):
                raise ValueError('Readonly sysfs flags missing')
            if any(field.startswith(('shared:', 'master:')) for field in fields[6:split]):
                raise ValueError('Sysfs mount propagation must be private')
            return line
    raise ValueError('Readonly namespace sysfs was not mounted')


def validate_names(attempt, unit, pid):
    if (not re.fullmatch(r'/var/tmp/ci222-server-[a-z0-9_]+/run-[a-z0-9_]+', str(attempt))
            or not re.fullmatch(r'ci222-server-[a-f0-9]{12}\.service', unit) or pid <= 1):
        raise ValueError('Only an exact reviewed pilot target is accepted')


def checked(value, operation):
    if value < 0:
        error = ctypes.get_errno()
        raise OSError(error, operation + ': ' + os.strerror(error))
    return value


def mount_api(libc):
    signatures = {
        'fsopen': [ctypes.c_char_p, ctypes.c_uint],
        'fsconfig': [ctypes.c_int, ctypes.c_uint, ctypes.c_char_p, ctypes.c_void_p, ctypes.c_int],
        'fsmount': [ctypes.c_int, ctypes.c_uint, ctypes.c_uint],
        'move_mount': [ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint],
        'mount': [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_char_p, ctypes.c_ulong, ctypes.c_char_p],
    }
    for name, arguments in signatures.items():
        function = getattr(libc, name)
        function.argtypes = arguments
        function.restype = ctypes.c_int


def bootstrap(attempt, unit, pid):
    validate_names(attempt, unit, pid)
    if os.geteuid() != 0 or os.readlink('/proc/self/ns/user') != os.readlink('/proc/1/ns/user'):
        raise ValueError('Host operator context required for bootstrap only')
    if os.readlink('/proc/self/ns/mnt') != os.readlink('/proc/1/ns/mnt'):
        raise ValueError('Initial operator mount context required for detached creation')
    for path in (attempt, attempt.parent):
        info = path.lstat()
        if not stat.S_ISDIR(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o077:
            raise ValueError('Pilot parent must be private and root-owned')
    root = attempt / 'root'
    if root.is_symlink(): raise ValueError('Linked pilot root refused')
    proc = Path('/proc') / str(pid)
    expected_group = '0::/system.slice/' + unit + '/controller/payload'
    if (proc / 'cgroup').read_text().strip() != expected_group:
        raise ValueError('Process is outside this pilot payload')
    uid = mapped_uid((proc / 'uid_map').read_text())
    if mapped_uid((proc / 'gid_map').read_text()) != uid:
        raise ValueError('UID/GID mappings differ')
    status = (proc / 'status').read_text().splitlines()
    nspid = next(line.split()[1:] for line in status if line.startswith('NSpid:'))
    if nspid != [str(pid), '1']:
        raise ValueError('Expected the waiting container init, not another process')
    if (proc / 'comm').read_text().strip() != 'sh':
        raise ValueError('Expected fixed bootstrap shell')
    if not os.path.samestat((proc / 'exe').stat(), (root / 'bin/busybox').stat()):
        raise ValueError('Unexpected pilot executable')
    identities = {name: os.readlink(proc / 'ns' / name) for name in ('user', 'mnt', 'net', 'pid', 'cgroup')}
    if any(value == os.readlink('/proc/self/ns/' + name) for name, value in identities.items()):
        raise ValueError('Pilot must have separate user/mount/network/PID/cgroup namespaces')
    fd_root = os.open(proc / 'root', os.O_RDONLY | os.O_DIRECTORY)
    fd_mnt = os.open(proc / 'ns/mnt', os.O_RDONLY)
    fd_net = os.open(proc / 'ns/net', os.O_RDONLY)
    # The target procfs cannot see this host-side helper PID. Pin our own proc
    # namespace directory before setns; its links still describe this task.
    fd_self_ns = os.open('/proc/self/ns', os.O_RDONLY | os.O_DIRECTORY)
    mountinfo = (proc / 'mountinfo').open()
    libc = ctypes.CDLL(None, use_errno=True)
    mount_api(libc)
    fd_mount = None
    try:
        if not os.path.samestat(os.fstat(fd_root), root.stat()):
            raise ValueError('Process root does not match staged pilot root')
        if (proc / 'cgroup').read_text().strip() != expected_group:
            raise ValueError('Pilot moved before namespace entry')
        for fd, kind in ((fd_mnt, 'mnt'), (fd_net, 'net')):
            if os.readlink('/proc/self/fd/' + str(fd)) != identities[kind]:
                raise ValueError('Namespace changed before bootstrap')
        os.setns(fd_net, CLONE_NEWNET)
        if os.readlink('net', dir_fd=fd_self_ns) != identities['net']:
            raise ValueError('Detached sysfs must use the private network namespace')
        # Create a detached mount, never attach anything to the host mount tree.
        # The filesystem context is bound to the verified private network above.
        # Creating it only after entering the less-privileged mount namespace is
        # rejected by the kernel's mount_too_revealing check even for host root.
        fsfd = checked(libc.fsopen(b'sysfs', 1), 'fsopen')  # FSOPEN_CLOEXEC
        try:
            checked(libc.fsconfig(fsfd, 6, None, None, 0), 'fsconfig create')
            fd_mount = checked(libc.fsmount(fsfd, 1, MOUNT_FLAGS), 'fsmount readonly')
        finally:
            os.close(fsfd)
        os.setns(fd_mnt, CLONE_NEWNS)
        if any(os.readlink(kind, dir_fd=fd_self_ns) != identities[kind] for kind in ('mnt', 'net')):
            raise ValueError('Did not enter the private pilot namespaces')
        os.fchdir(fd_root); os.chroot('.'); os.chdir('/')
        target = Path(DESTINATION)
        if target.is_symlink() or not target.is_dir() or target.stat().st_uid != uid:
            raise ValueError('Fixed private mount destination missing')
        if Path('/run/ci222-sysfs-ready').exists():
            raise ValueError('Bootstrap must be fresh')
        # Attach only in the verified private mount namespace, NOT a host bind.
        checked(libc.move_mount(fd_mount, b'', -100, DESTINATION.encode(), 4), 'move_mount private')
        checked(libc.mount(None, DESTINATION.encode(), None, (1 << 18) | (1 << 14), None),
                'make new sysfs mount private')  # MS_PRIVATE | MS_REC, this mount only
        mountinfo.seek(0)
        record = mount_record(mountinfo.read())
        # This helper returns and exits before the operator releases the shell.
        print('PRIVATE_SYSFS_BOOTSTRAP: ' + json.dumps({'mount': record, 'namespaces': identities}), flush=True)
    finally:
        if fd_mount is not None: os.close(fd_mount)
        mountinfo.close()
        for fd in (fd_root, fd_mnt, fd_net, fd_self_ns): os.close(fd)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--attempt', required=True, type=Path)
    parser.add_argument('--unit', required=True)
    parser.add_argument('--pid', required=True, type=int)
    args = parser.parse_args()
    bootstrap(args.attempt, args.unit, args.pid)


if __name__ == '__main__':
    main()
