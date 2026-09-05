import importlib.util
from pathlib import Path, PurePosixPath
import unittest

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('sysfs_bootstrap', HERE / 'ci-server-sysfs-bootstrap.py')
bootstrap = importlib.util.module_from_spec(spec); spec.loader.exec_module(bootstrap)


class SysfsBootstrapTests(unittest.TestCase):
    def test_host_mapping_refused(self):
        for value in ('0 0 4294967295', '0 1001 1\n1 165536 65536', '0 0 65536', '0 1001 65536'):
            with self.assertRaises(ValueError): bootstrap.mapped_uid(value)
        self.assertEqual(bootstrap.mapped_uid('0 201588736 65536'), 201588736)

    def test_exact_private_target_only(self):
        bootstrap.validate_names(PurePosixPath('/var/tmp/ci222-server-abcd/run-efgh'), 'ci222-server-0123456789ab.service', 100)
        for path in ('/home/ubuntu/S15P21E106', '/var/tmp/ci222-server-abcd/run-efgh/../root'):
            with self.assertRaises(ValueError):
                bootstrap.validate_names(PurePosixPath(path), 'ci222-server-0123456789ab.service', 100)
        with self.assertRaises(ValueError):
            bootstrap.validate_names(PurePosixPath('/var/tmp/ci222-server-abcd/run-efgh'), 'gitlab-runner.service', 100)

    def test_full_readonly_sysfs_required(self):
        line = '99 20 0:34 / /run/ci222-sysfs ro,nosuid,nodev,noexec - sysfs sysfs ro'
        self.assertEqual(bootstrap.mount_record(line), line)
        for invalid in (line.replace('ro,nosuid', 'rw,nosuid'), line.replace(' / /run', ' /devices /run'),
                        line.replace(' - sysfs ', ' - tmpfs '), line.replace(' - ', ' shared:5 - '), ''):
            with self.assertRaises(ValueError): bootstrap.mount_record(invalid)

    def test_no_host_bind_or_user_namespace_entry(self):
        source = (HERE / 'ci-server-sysfs-bootstrap.py').read_text()
        self.assertEqual(bootstrap.MOUNT_FLAGS, 15)
        self.assertIn("libc.fsopen(b'sysfs', 1)", source)
        self.assertIn('libc.fsmount(fsfd, 1, MOUNT_FLAGS)', source)
        self.assertIn("libc.move_mount(fd_mount, b'', -100, DESTINATION.encode(), 4)", source)
        self.assertLess(source.index('os.setns(fd_net'), source.index("libc.fsopen(b'sysfs'"))
        self.assertLess(source.index('libc.fsmount(fsfd'), source.index('os.setns(fd_mnt'))
        self.assertLess(source.index("os.chroot('.')"), source.index('libc.move_mount(fd_mount'))
        self.assertIn('os.setns(fd_net, CLONE_NEWNET)', source)
        self.assertIn('os.setns(fd_mnt, CLONE_NEWNS)', source)
        self.assertNotIn('MS_BIND =', source)
        self.assertNotIn('os.setns(fd_user', source)
        self.assertIn("os.fchdir(fd_root); os.chroot('.'); os.chdir('/')", source)

    def test_gate_is_released_after_helper_exit(self):
        source = (HERE / 'ci-server-manager-pilot.py').read_text()
        helper = source.index("str(base / 'scripts/ci-server-sysfs-bootstrap.py')")
        release = source.index("fd_ready = os.open('run/ci222-sysfs-ready'")
        self.assertLess(helper, release)
        self.assertIn('os.O_EXCL | os.O_NOFOLLOW', source)

    def test_namespace_recheck_uses_pinned_self_proc_directory(self):
        source = (HERE / 'ci-server-sysfs-bootstrap.py').read_text()
        self.assertLess(source.index("os.open('/proc/self/ns'"), source.index('os.setns(fd_net'))
        self.assertIn('os.readlink(kind, dir_fd=fd_self_ns)', source)
        self.assertIn('(fd_root, fd_mnt, fd_net, fd_self_ns)', source)


if __name__ == '__main__': unittest.main()
