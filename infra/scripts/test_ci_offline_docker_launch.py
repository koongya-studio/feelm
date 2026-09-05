import importlib.util
from pathlib import Path
import unittest

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('offline_launch', HERE / 'ci-offline-docker-launch.py')
launch = importlib.util.module_from_spec(spec)
spec.loader.exec_module(launch)


class OfflineDockerTests(unittest.TestCase):
    def test_loopback_only(self):
        launch.validate_network([{'ifname': 'lo'}], [], [])

    def test_host_network_rejected(self):
        for links, v4, v6 in [([], [], []), ([{'ifname': 'eth0'}], [], []),
                              ([{'ifname': 'lo'}, {'ifname': 'eth0'}], [], []),
                              ([{'ifname': 'lo'}], [{'dst': 'default'}], []),
                              ([{'ifname': 'lo'}], [], [{'dst': 'default'}])]:
            with self.assertRaises(ValueError):
                launch.validate_network(links, v4, v6)

    def test_separate_paths_and_clean_environment(self):
        cmd = launch.command()
        self.assertIn('--host=unix:///run/feelm-ci222-offline/docker.sock', cmd)
        self.assertIn('--data-root=/home/feelm-ci-test/offline-docker-222/data/docker', cmd)
        env = launch.environment()
        self.assertEqual(env['DOCKERD_ROOTLESS_ROOTLESSKIT_DETACH_NETNS'], 'false')
        self.assertNotIn('HTTP_PROXY', env)
        self.assertNotIn('DOCKER_HOST', env)
        self.assertNotIn('DOCKERD_ROOTLESS_ROOTLESSKIT_FLAGS', env)

    def test_explicit_user_bus_with_separate_runtime(self):
        env = launch.environment()
        self.assertEqual(env['DBUS_SESSION_BUS_ADDRESS'], 'unix:path=/run/user/1001/bus')
        self.assertNotEqual(env['XDG_RUNTIME_DIR'], '/run/user/1001')

    def test_unit_boundary_and_bounded_lifetime(self):
        unit = (HERE / 'systemd/feelm-ci222-offline-docker.service').read_text()
        for line in ('PrivateNetwork=yes', 'User=feelm-ci-test', 'Slice=user-1001.slice',
                     'KillMode=control-group', 'Restart=no', 'RuntimeMaxSec=3600'):
            self.assertIn(line, unit.splitlines())
        self.assertNotIn('[Install]', unit)
        self.assertNotIn('ExecStop=', unit)


if __name__ == '__main__':
    unittest.main()
