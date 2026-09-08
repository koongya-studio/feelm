import importlib.util
import io
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

spec = importlib.util.spec_from_file_location('backend_runtime', Path(__file__).with_name('ci-local-backend-runtime.py'))
runtime = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime)


class RuntimeTests(unittest.TestCase):
    def test_manifest_hash_can_be_supplied_by_server_preparation(self):
        with patch.object(runtime.inputs, 'inspect_bundle', side_effect=RuntimeError('checked')) as inspect, \
                tempfile.TemporaryDirectory() as directory, self.assertRaisesRegex(RuntimeError, 'checked'):
            runtime.prepare(Path(directory), Path(directory) / 'root', lambda *_: None,
                            manifest_sha='a' * 64)
        inspect.assert_called_once_with(Path(directory), 'a' * 64, runtime.JDK_SHA)

    def test_bundle_source_digest_is_used_for_dynamic_stage_snapshot(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            bundle = base / 'bundle'; bundle.mkdir()
            root = base / 'root'; root.mkdir()
            payload = b'dynamic reviewed source'
            (bundle / 'backend.tar').write_bytes(payload)
            with patch.object(runtime.inputs, 'inspect_bundle', return_value={}), \
                    patch.object(runtime, 'source_installer', create=True) as installer:
                installer.side_effect = RuntimeError('digest checked')
                with self.assertRaisesRegex(RuntimeError, 'digest checked'):
                    runtime.prepare(bundle, root, lambda *_: None,
                                    source_installer=installer,
                                    bundle_source_sha='d' * 64)
            installer.assert_called_once_with(payload, 'd' * 64, root / 'work')

    def test_payload_is_offline_full_suite_and_cleanup(self):
        for expected in ('clean test spotlessCheck --offline', '--no-daemon',
                         'TESTCONTAINERS_RYUK_DISABLED=false', 'remaining-containers', 'gradle-exit'):
            self.assertIn(expected, runtime.PAYLOAD)
        self.assertNotIn('docker pull', runtime.PAYLOAD)
        self.assertNotIn('spotlessApply', runtime.PAYLOAD)

    def test_only_portable_cache_subtrees_are_copied(self):
        source = Path(runtime.__file__).read_text()
        self.assertIn("for selected in ('caches/modules-2', 'wrapper/dists')", source)
        self.assertNotIn("shutil.copytree(bundle / 'gradle-cache',", source)

    def test_jdk_symlink_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive = root / 'jdk.tar.gz'
            with tarfile.open(archive, 'w:gz') as stream:
                item = tarfile.TarInfo('jdk-21.0.12.1+1/bin/java')
                item.type = tarfile.SYMTYPE; item.linkname = '/outside'
                stream.addfile(item)
            with self.assertRaises(ValueError):
                runtime.extract_jdk(archive, root / 'output')

    def test_jdk_regular_file_extracted(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive = root / 'jdk.tar.gz'
            with tarfile.open(archive, 'w:gz') as stream:
                item = tarfile.TarInfo('jdk-21.0.12.1+1/bin/java')
                item.size = 1; item.mode = 0o755
                stream.addfile(item, io.BytesIO(b'x'))
            runtime.extract_jdk(archive, root / 'output')
            self.assertEqual((root / 'output/bin/java').read_bytes(), b'x')

    def test_license_link_materializes_regular_file(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); archive = root / 'jdk.tar.gz'
            with tarfile.open(archive, 'w:gz') as stream:
                original = tarfile.TarInfo('jdk-21.0.12.1+1/legal/java.base/LICENSE')
                original.size = 7
                stream.addfile(original, io.BytesIO(b'license'))
                link = tarfile.TarInfo('jdk-21.0.12.1+1/legal/java.xml/LICENSE')
                link.type = tarfile.SYMTYPE; link.linkname = '../java.base/LICENSE'
                stream.addfile(link)
            runtime.extract_jdk(archive, root / 'output')
            target = root / 'output/legal/java.xml/LICENSE'
            self.assertFalse(target.is_symlink())
            self.assertEqual(target.read_bytes(), b'license')

    def test_missing_execution_marker_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); (root / 'work').mkdir()
            self.assertFalse(runtime.report(root, 0))

    def test_evidence_destination_must_be_new(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            destination = root / 'existing'; destination.mkdir()
            with self.assertRaises(FileExistsError):
                runtime.persist_evidence(root, root, destination)

    def test_reports_do_not_override_process_failure_or_skips(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); work = root / 'work'; work.mkdir()
            (work / 'gradle-exit').write_text('0')
            (work / 'remaining-containers').write_text('')
            reports = work / 'backend/build/test-results/test'; reports.mkdir(parents=True)
            tree = ET.Element('testsuite', tests='90', skipped='0')
            ET.ElementTree(tree).write(reports / 'TEST-fixture.xml')
            with patch.object(runtime.inputs.prep.pilot, 'report_summary', return_value=True):
                self.assertTrue(runtime.report(root, 0))
                self.assertFalse(runtime.report(root, 125))
                tree.set('skipped', '1'); ET.ElementTree(tree).write(reports / 'TEST-fixture.xml')
                self.assertFalse(runtime.report(root, 0))


if __name__ == '__main__':
    unittest.main()
