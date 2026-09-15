import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('local_inputs',
    Path(__file__).with_name('ci-local-backend-inputs.py'))
inputs = importlib.util.module_from_spec(spec)
spec.loader.exec_module(inputs)


class LocalInputsTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)

    def bundle(self):
        data = io.BytesIO()
        with tarfile.open(fileobj=data, mode='w') as archive:
            for name in ('gradlew', 'build.gradle', 'settings.gradle', 'gradle/wrapper/gradle-wrapper.jar'):
                entry = tarfile.TarInfo('backend/' + name)
                entry.size = 1
                archive.addfile(entry, io.BytesIO(b'x'))
        source = data.getvalue()
        (self.root / 'backend.tar').write_bytes(source)
        (self.root / 'backend-images.tar').write_bytes(b'fixture image archive')
        (self.root / 'jdk.tar.gz').write_bytes(b'fixture JDK hash only, not a runtime')
        cache = self.root / 'gradle-cache'
        for name in ('caches/modules-2/metadata.bin', 'wrapper/dists/x/gradle-9.7.1/bin/gradle'):
            path = cache / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b'fixture')
        sha = hashlib.sha256(source).hexdigest()
        inventory, _ = inputs.prep.cache_inventory(cache)
        manifest = {'revision': inputs.prep.REVISION, 'archive_sha256': sha, 'cache': inventory}
        (self.root / 'cache-manifest.json').write_text(json.dumps(manifest))
        self.addCleanup(patch.stopall)
        patch.object(inputs.prep, 'ARCHIVE_SHA', sha).start()
        patch.object(inputs, 'IMAGE_ARCHIVE_SHA', inputs.prep.digest(self.root / 'backend-images.tar')).start()
        return inputs.prep.digest(self.root / 'cache-manifest.json'), inputs.prep.digest(self.root / 'jdk.tar.gz')

    def test_valid_audit_does_not_claim_execution_or_java_version(self):
        manifest, jdk = self.bundle()
        result = inputs.inspect_bundle(self.root, manifest, jdk)
        self.assertFalse(result['tests_executed'])
        self.assertFalse(result['java21_runtime_verified'])
        self.assertFalse(result['runner_approved'])

    def test_changed_cache_rejected(self):
        manifest, jdk = self.bundle()
        (self.root / 'gradle-cache/caches/modules-2/metadata.bin').write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'Cache inventory'):
            inputs.inspect_bundle(self.root, manifest, jdk)

    def test_extra_home_properties_rejected(self):
        manifest, jdk = self.bundle()
        (self.root / 'gradle-cache/gradle.properties').write_text('synthetic=not-a-secret')
        with self.assertRaisesRegex(ValueError, 'unrecorded'):
            inputs.inspect_bundle(self.root, manifest, jdk)

    def test_wrong_jdk_hash_rejected(self):
        manifest, _ = self.bundle()
        with self.assertRaisesRegex(ValueError, 'SHA256 mismatch'):
            inputs.inspect_bundle(self.root, manifest, '0' * 64)

    def test_manifest_hash_required(self):
        _, jdk = self.bundle()
        with self.assertRaisesRegex(ValueError, 'Reviewed SHA256'):
            inputs.inspect_bundle(self.root, '', jdk)

    def test_extra_bundle_file_rejected(self):
        manifest, jdk = self.bundle()
        (self.root / 'old-report.xml').write_text('old')
        with self.assertRaisesRegex(ValueError, 'Expected only'):
            inputs.inspect_bundle(self.root, manifest, jdk)

    def test_duplicate_manifest_keys_rejected(self):
        with self.assertRaisesRegex(ValueError, 'Duplicate'):
            inputs.unique_json([('cache', {}), ('cache', {})])

    def test_export_never_overwrites(self):
        target = self.root / 'backend.tar'
        target.write_bytes(b'keep')
        with patch.object(inputs, 'source_bytes', return_value=b'new'):
            with self.assertRaises(FileExistsError):
                inputs.export_source(self.root, target)
        self.assertEqual(target.read_bytes(), b'keep')


if __name__ == '__main__':
    unittest.main()
