import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('gitlab_install', HERE / 'ci-gitlab-server-install.py')
installer = importlib.util.module_from_spec(spec); spec.loader.exec_module(installer)


class InstallerTests(unittest.TestCase):
    def test_default_is_plan_only(self):
        with patch('sys.argv', ['installer']), patch.object(installer, 'install') as install:
            self.assertEqual(installer.main(), 0); install.assert_not_called()

    def test_paths_are_separate_and_no_token_or_existing_runner_config(self):
        source = Path(installer.__file__).read_text()
        self.assertEqual(installer.TARGET.as_posix(), '/usr/local/lib/feelm-ci222/gitlab')
        self.assertEqual(installer.RUNNER_HOME.as_posix(), '/home/feelm-ci-test/gitlab-runner')
        self.assertNotIn('/etc/gitlab-runner/config.toml', source)
        self.assertNotIn('gitlab-runner register', source)
        self.assertNotIn('systemctl\', \'enable', source)
        self.assertNotIn('systemctl\', \'start', source)
        self.assertIn('/usr/local/lib/feelm-ci222/gitlab/ci-gitlab-job-adapter.py run', installer.SUDOERS_LINE)
        self.assertIn('/usr/local/lib/feelm-ci222/gitlab/ci-dependency-cache.py', installer.SUDOERS_LINE)
        self.assertIn('gitlab-runner ALL=(root) NOPASSWD:', installer.SUDOERS_LINE)
        self.assertNotIn('ubuntu ALL=(root) NOPASSWD:', installer.SUDOERS_LINE)

    @unittest.skipUnless(installer.pwd is not None, 'Linux account database required')
    def test_preflight_refuses_any_existing_target(self):
        with patch.object(installer.os, 'geteuid', return_value=0), \
             patch.object(installer, 'load_pilot') as load, \
             patch.object(installer.pwd, 'getpwnam') as account, \
             patch.object(installer.Path, 'is_file', return_value=True), \
             patch.object(installer.Path, 'exists', return_value=True):
            load.return_value.check_base.return_value = None
            account.return_value = type('Account', (), {'pw_uid': 1001, 'pw_gid': 1001,
                                                        'pw_dir': '/home/feelm-ci-test'})()
            with self.assertRaises(FileExistsError): installer.preflight()


if __name__ == '__main__': unittest.main()
