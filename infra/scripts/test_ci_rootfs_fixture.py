import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('rootfs_fixture', Path(__file__).with_name('ci-rootfs-fixture.py'))
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)


class RootfsFixtureTests(unittest.TestCase):
    def test_plan_no_run(self):
        with patch('sys.argv', ['fixture']), patch.object(fixture, 'run') as run:
            self.assertEqual(fixture.main(), 0)
            run.assert_not_called()

    def test_confirm_required(self):
        with patch('sys.argv', ['fixture', 'run']), patch.object(fixture, 'run') as run:
            with self.assertRaises(SystemExit):
                fixture.main()
            run.assert_not_called()

    def test_namespace_and_outer_uid_options(self):
        cmd = fixture.command(Path('/fixed/script'), Path('/fixed/root'))
        for option in ('--reuid=65534', '--regid=65534', '--clear-groups', '--map-root-user',
                       '--mount', '--pid', '--net', '--kill-child=KILL', '--propagation=private'):
            self.assertIn(option, cmd)

    def test_negative_checks_need_permission_error(self):
        self.assertNotIn('2>/dev/null', fixture.PROBE)
        self.assertIn('UNEXPECTED_MOUNT_ERROR', fixture.PROBE)
        self.assertIn('UNEXPECTED_WRITE_ERROR', fixture.PROBE)
        self.assertIn('0 65534 1', fixture.PROBE)
        self.assertIn('CapBnd', fixture.PROBE)
        self.assertIn('HOST_SOCKET', fixture.PROBE)

    def test_docker_attempt_is_bounded_and_never_claims_success(self):
        self.assertIn('timeout -s TERM 12', fixture.DOCKER_PROBE)
        self.assertIn('--rootless', fixture.DOCKER_PROBE)
        self.assertIn('DOCKER_COMPATIBILITY: NOT_PASSED', fixture.DOCKER_PROBE)
        self.assertIn('exit 2', fixture.DOCKER_PROBE)
        self.assertNotIn('--privileged', fixture.DOCKER_PROBE)
        self.assertNotIn('--iptables=false', fixture.DOCKER_PROBE)


if __name__ == '__main__':
    unittest.main()
