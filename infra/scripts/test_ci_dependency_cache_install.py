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

    def test_installer_preserves_previous_versions_and_has_rollback(self):
        source = Path(installer.__file__).read_text()
        self.assertIn("'.previous-'", source)
        self.assertIn('os.replace(backup, TARGET)', source)
        self.assertIn('os.replace(sudoers_backup, SUDOERS)', source)
        self.assertNotIn('rmtree(', source)
        self.assertIn("'ci222-owner-*.service'", source)
        self.assertIn('/usr/local/lib/feelm-ci222/gitlab/ci-dependency-cache.py',
                      installer.SUDOERS_TEXT)


if __name__ == '__main__':
    unittest.main()
