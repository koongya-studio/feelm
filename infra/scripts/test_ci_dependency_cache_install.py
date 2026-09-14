import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('cache_install', HERE / 'ci-dependency-cache-install.py')
installer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(installer)


class CacheInstallTests(unittest.TestCase):
    def test_default_is_read_only_plan(self):
        with patch('sys.argv', ['installer']), patch.object(installer, 'update') as update:
            self.assertEqual(installer.main(), 0)
            update.assert_not_called()

    def test_confirmed_update_requires_matching_prepared_base(self):
        with patch('sys.argv', ['installer', '--confirm-update',
                                '--operator-username', 'infra.user']), \
             patch.object(installer, 'update') as update, \
             self.assertRaisesRegex(ValueError, 'matching prepared base'):
            installer.main()
        update.assert_not_called()

    def test_operator_usernames_are_explicit_and_bounded(self):
        self.assertEqual(installer.normalized_operators(['infra.user', 'infra.user']),
                         ['infra.user'])
        for values in ([], ['bad user'], ['x' * 65]):
            with self.subTest(values=values), self.assertRaises(ValueError):
                installer.normalized_operators(values)

    def test_installer_preserves_previous_versions_and_has_rollback(self):
        source = Path(installer.__file__).read_text()
        self.assertIn("'.previous-'", source)
        self.assertIn('os.replace(backup, TARGET)', source)
        self.assertIn('os.replace(sudoers_backup, SUDOERS)', source)
        self.assertIn('os.replace(config_backup, CONFIG)', source)
        self.assertIn("runtime_contract().require_same(HERE, prepared_base / 'scripts')", source)
        self.assertNotIn('rmtree(', source)
        self.assertIn("'ci222-owner-*.service'", source)
        self.assertIn('/usr/local/lib/feelm-ci222/gitlab/ci-dependency-cache.py',
                      installer.SUDOERS_TEXT)
        self.assertIn('gitlab-runner ALL=(root) NOPASSWD:', installer.SUDOERS_TEXT)
        self.assertNotIn('ubuntu ALL=(root) NOPASSWD:', installer.SUDOERS_TEXT)
        self.assertLess(source.index("if active:"), source.index("prefix='.gitlab-ci271-'"))


if __name__ == '__main__':
    unittest.main()
