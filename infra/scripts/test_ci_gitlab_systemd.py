from pathlib import Path
import unittest

HERE = Path(__file__).resolve().parent


class GitLabRunnerUnitTests(unittest.TestCase):
    def test_runner_is_separate_and_guard_gated(self):
        unit = (HERE / 'systemd/feelm-ci222-gitlab-runner.service').read_text()
        self.assertIn('User=feelm-ci-test', unit)
        self.assertIn('Group=feelm-ci-test', unit)
        self.assertIn('Requires=feelm-ci-guard.service', unit)
        self.assertIn('/home/feelm-ci-test/gitlab-runner/config.toml', unit)
        self.assertNotIn('/etc/gitlab-runner/config.toml', unit)
        self.assertNotIn('/var/run/docker.sock', unit)
        self.assertIn('KillMode=control-group', unit)


if __name__ == '__main__': unittest.main()
