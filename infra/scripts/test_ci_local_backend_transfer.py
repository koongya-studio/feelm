import importlib.util
from pathlib import Path
import tarfile
import unittest

spec = importlib.util.spec_from_file_location('transfer', Path(__file__).with_name('ci-local-backend-transfer.py'))
transfer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(transfer)


class TransferTests(unittest.TestCase):
    def test_remote_uses_central_reviewed_image_archive_identity(self):
        self.assertIn(str(transfer.inputs.IMAGE_ARCHIVE_PATH), transfer.REMOTE)
        self.assertIn(transfer.inputs.IMAGE_ARCHIVE_SHA, transfer.REMOTE)
        self.assertNotIn('__IMAGE_ARCHIVE_', transfer.REMOTE)

    def test_only_selected_regular_paths(self):
        for name in ('cache-manifest.json', 'backend-images.tar', 'gradle-cache/caches/a',
                     'gradle-cache/wrapper/dists/a'):
            self.assertTrue(transfer.safe_member(tarfile.TarInfo(name)))
        for name in ('../outside', '/absolute', 'gradle-cache/caches/../../outside',
                     'gradle-cache/gradle.properties', '.ssh/key', 'backend.tar',
                     'gradle-cache/caches/a\\..\\outside'):
            self.assertFalse(transfer.safe_member(tarfile.TarInfo(name)))

    def test_link_and_device_refused(self):
        for kind in (tarfile.SYMTYPE, tarfile.LNKTYPE, tarfile.CHRTYPE):
            entry = tarfile.TarInfo('gradle-cache/caches/a')
            entry.type = kind
            self.assertFalse(transfer.safe_member(entry))

    def test_remote_program_compiles_without_executing(self):
        compile(transfer.REMOTE, '<remote-export>', 'exec')
        self.assertIn('os.O_RDONLY | os.O_NOFOLLOW', transfer.REMOTE)
        self.assertNotIn('subprocess', transfer.REMOTE)


if __name__ == '__main__':
    unittest.main()
