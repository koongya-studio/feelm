import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('job_io', Path(__file__).with_name('ci-job-io.py'))
job = importlib.util.module_from_spec(spec); spec.loader.exec_module(job)


def archive(entries):
    output = io.BytesIO()
    with tarfile.open(fileobj=output, mode='w') as stream:
        for name, data, kind, mode in entries:
            item = tarfile.TarInfo(name); item.type = kind; item.mode = mode
            item.size = len(data); item.linkname = '/etc/passwd' if kind in (tarfile.SYMTYPE, tarfile.LNKTYPE) else ''
            stream.addfile(item, io.BytesIO(data))
    data = output.getvalue()
    return data, hashlib.sha256(data).hexdigest()


def regular(name='backend/a.txt', data=b'hello', mode=0o644):
    return name, data, tarfile.REGTYPE, mode


def xml(extra='', tests=1, failures=0, skipped=0, classname='com.example.Tests',
        elapsed='0.125', suite_elapsed='0.250'):
    return (f'<testsuite tests="{tests}" failures="{failures}" errors="0" skipped="{skipped}" '
            f'time="{suite_elapsed}">'
            f'<testcase classname="{classname}" name="test" time="{elapsed}">'
            f'{extra}</testcase></testsuite>').encode()


def timing(test_count=1):
    return json.dumps({
        'schemaVersion': 1,
        'gradleSeconds': 12.345,
        'slowestTasks': [{'path': ':test', 'seconds': 8.5}],
        'testCount': test_count,
        'slowestClasses': [
            {'className': 'com.example.Tests', 'seconds': 0.125, 'tests': 1}
        ],
        'informationalOnly': True,
    }).encode()


class InputValidationTests(unittest.TestCase):
    def test_regular_archive(self):
        files, directories = job.source_plan(*archive([regular(), regular('backend/gradlew', b'#!/bin/sh', 0o755)]))
        self.assertEqual(files['backend/gradlew'][1], 0o700)
        self.assertEqual(directories, {'backend'})

    def test_hash_and_archive_limit(self):
        data, _ = archive([regular()])
        with self.assertRaises(ValueError): job.source_plan(data, '0' * 64)
        with patch.object(job, 'MAX_ARCHIVE', 8):
            with self.assertRaises(ValueError): job.source_plan(data, hashlib.sha256(data).hexdigest())

    def test_paths_fail_closed(self):
        for name in ('../escape', '/backend/a', 'backend/../a', 'backend//a', './backend/a',
                     'backend\\a', 'backend/a:b', 'backend/a\x00b', 'backend/e\u0301',
                     'backend/' + 'x' * 256):
            with self.subTest(name=name), self.assertRaises(ValueError): job.relative_parts(name)

    def test_no_secret_git_cache_or_other_component(self):
        for name in ('frontend/a', 'backend/.git/config', 'backend/.env',
                     'backend/sub/.env.local', 'backend/.gradle/cache'):
            with self.subTest(name=name), self.assertRaises(ValueError):
                job.source_plan(*archive([regular(name)]))

    def test_tracked_environment_example_allowed_not_actual_environment(self):
        files, _ = job.source_plan(*archive([regular('backend/.env.example', b'DB_PASSWORD=\n')]))
        self.assertIn('backend/.env.example', files)
        with self.assertRaises(ValueError):
            job.source_plan(*archive([regular('backend/.ENV.EXAMPLE')]))

    def test_fixed_reviewed_repository_archive_accepted(self):
        import subprocess
        repo = Path(__file__).resolve().parents[2]
        result = subprocess.run(['git', '-C', str(repo), 'archive', '--format=tar',
                                 '9631afd9c616e047abbdd0ad7bf8192231f7f35c', 'backend'],
                                capture_output=True, check=True, timeout=30)
        files, _ = job.source_plan(result.stdout, hashlib.sha256(result.stdout).hexdigest())
        self.assertIn('backend/gradlew', files)

    def test_links_and_special_entries(self):
        for kind in (tarfile.SYMTYPE, tarfile.LNKTYPE, tarfile.FIFOTYPE, tarfile.CHRTYPE, tarfile.BLKTYPE):
            with self.subTest(kind=kind), self.assertRaises(ValueError):
                job.source_plan(*archive([('backend/link', b'', kind, 0o644)]))

    def test_setuid_refused(self):
        with self.assertRaises(ValueError): job.source_plan(*archive([regular(mode=0o4755)]))

    def test_duplicates_and_collisions_both_orders(self):
        for entries in ([regular(), regular()], [regular('backend/a'), regular('backend/a/b')],
                        [regular('backend/a/b'), regular('backend/a')]):
            with self.assertRaises(ValueError): job.source_plan(*archive(entries))

    def test_file_count_and_size_caps(self):
        with patch.object(job, 'MAX_SOURCE_FILES', 1):
            with self.assertRaises(ValueError): job.source_plan(*archive([regular(), regular('backend/b')]))
        with patch.object(job, 'MAX_SOURCE_FILE', 2):
            with self.assertRaises(ValueError): job.source_plan(*archive([regular()]))

    def test_empty_archive(self):
        with self.assertRaises(ValueError): job.source_plan(*archive([]))

    def test_compressed_archive_refused(self):
        import gzip
        data = gzip.compress(archive([regular()])[0])
        with self.assertRaises(tarfile.ReadError): job.source_plan(data, hashlib.sha256(data).hexdigest())

    def test_junit_valid(self):
        counts, classes = job.junit_summary(xml())
        self.assertEqual(counts, {'tests': 1, 'failures': 0, 'errors': 0, 'skipped': 0})
        self.assertEqual(classes, {'com.example.Tests'})

    def test_junit_display_name_not_class_identity(self):
        data = xml().replace(b'<testsuite ', '<testsuite name="한글 표시 이름" '.encode())
        self.assertEqual(job.junit_summary(data)[1], {'com.example.Tests'})

    def test_junit_counts_must_match(self):
        for data in (xml(tests=2), xml('<failure/>'), xml('<skipped/>'), xml(classname='')):
            with self.assertRaises(ValueError): job.junit_summary(data)

    def test_junit_failed_and_skipped_are_preserved(self):
        self.assertEqual(job.junit_summary(xml('<failure/>', failures=1))[0]['failures'], 1)
        self.assertEqual(job.junit_summary(xml('<skipped/>', skipped=1))[0]['skipped'], 1)

    def test_junit_class_timing_is_aggregated(self):
        data = (b'<testsuite tests="2" failures="0" errors="0" skipped="0" time="2.000">'
                b'<testcase classname="com.example.Tests" name="one" time="0.125"/>'
                b'<testcase classname="com.example.Tests" name="two" time="1.250"/>'
                b'</testsuite>')
        self.assertEqual(job.junit_class_timings(data), {
            'com.example.Tests': {'seconds': job.Decimal('1.375'), 'tests': 2}
        })
        self.assertEqual(job.junit_lifecycle_timing(data), ('com.example.Tests', {
            'suite_seconds': job.Decimal('2.000'),
            'case_seconds': job.Decimal('1.375'),
            'lifecycle_seconds': job.Decimal('0.625'),
            'tests': 2,
        }))

    def test_junit_timing_rejects_non_finite_missing_or_unsafe_values(self):
        for elapsed in ('', 'NaN', '-1', '1e3', '86401'):
            with self.subTest(elapsed=elapsed), self.assertRaises(ValueError):
                job.junit_class_timings(xml(elapsed=elapsed))
        for elapsed in ('', 'NaN', '-1', '1e3', '86401'):
            with self.subTest(suite_elapsed=elapsed), self.assertRaises(ValueError):
                job.junit_lifecycle_timing(xml(suite_elapsed=elapsed))
        with self.assertRaisesRegex(ValueError, 'shorter'):
            job.junit_lifecycle_timing(xml(elapsed='2.0', suite_elapsed='1.0'))
        rounded = job.junit_lifecycle_timing(xml(elapsed='0.125', suite_elapsed='0.124'))[1]
        self.assertEqual(rounded['suite_seconds'], job.Decimal('0.125'))
        self.assertEqual(rounded['lifecycle_seconds'], job.Decimal('0'))
        with self.assertRaises(ValueError):
            job.junit_class_timings(xml(classname='bad\nlog'))

    def test_gradle_timing_is_bounded_and_public_summary_is_validated(self):
        gradle = job.gradle_timing(timing())
        summary = job.public_timing(
            gradle,
            {'com.example.Tests': {'seconds': job.Decimal('0.125'), 'tests': 1}},
            {'com.example.Tests': {'suite_seconds': job.Decimal('0.250'),
                                   'case_seconds': job.Decimal('0.125'),
                                   'lifecycle_seconds': job.Decimal('0.125'), 'tests': 1}})
        self.assertEqual(job.validated_public_timing(summary), summary)
        self.assertEqual(summary['schema_version'], 2)
        self.assertEqual(summary['test_task_seconds'], 8.5)
        self.assertEqual(summary['test_suite_seconds'], 0.25)
        self.assertEqual(summary['test_class_lifecycle_seconds'], 0.125)
        self.assertEqual(summary['test_task_unattributed_seconds'], 8.25)
        changed = dict(summary, gradle_seconds=float('nan'))
        with self.assertRaises(ValueError):
            job.validated_public_timing(changed)
        inconsistent = dict(summary, test_class_lifecycle_seconds=1.0)
        with self.assertRaisesRegex(ValueError, 'Inconsistent'):
            job.validated_public_timing(inconsistent)
        oversized = dict(summary, slowest_lifecycle_classes=(
            summary['slowest_lifecycle_classes'] * 11))
        with self.assertRaisesRegex(ValueError, 'schema'):
            job.validated_public_timing(oversized)

    def test_public_timing_requires_matching_lifecycle_source(self):
        gradle = job.gradle_timing(timing())
        classes = {'com.example.Tests': {'seconds': job.Decimal('0.125'), 'tests': 1}}
        with self.assertRaisesRegex(ValueError, 'sets differ'):
            job.public_timing(gradle, classes, {})
        lifecycle = {'com.example.Tests': {
            'suite_seconds': job.Decimal('0.250'),
            'case_seconds': job.Decimal('0.100'),
            'lifecycle_seconds': job.Decimal('0.150'),
            'tests': 1,
        }}
        with self.assertRaisesRegex(ValueError, 'values differ'):
            job.public_timing(gradle, classes, lifecycle)

    def test_junit_dtd_utf16_and_deep_nesting_refused(self):
        for data in (b'<!DOCTYPE testsuite [<!ENTITY x "abc">]>' + xml(),
                     xml().decode().encode('utf-16'), xml('<x>' * 40 + '</x>' * 40)):
            with self.assertRaises((ValueError, UnicodeError)): job.junit_summary(data)


@unittest.skipUnless(sys.platform.startswith('linux'), 'Linux dirfd security tests')
class LinuxBoundaryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='ci222-io-')
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.root = self.base / 'root'; self.root.mkdir(mode=0o700)
        self.output = self.base / 'output'; self.output.mkdir(mode=0o700)

    def reports(self, data=None):
        directory = self.root / job.REPORT_DIRECTORY
        directory.mkdir(parents=True)
        path = directory / 'TEST-com.example.Tests.xml'
        path.write_bytes(xml() if data is None else data)
        timing_path = self.root / job.TIMING_FILE
        timing_path.parent.mkdir(parents=True)
        timing_path.write_bytes(timing())
        return path

    def test_install_materializes_only_files(self):
        result = job.install_snapshot(*archive([regular(), regular('backend/gradlew', b'echo hello', 0o755)]), self.root)
        self.assertEqual(result['files'], 2)
        self.assertEqual((self.root / 'backend/a.txt').read_bytes(), b'hello')
        self.assertEqual((self.root / 'backend/gradlew').stat().st_mode & 0o777, 0o700)

    def test_existing_or_nonprivate_destination_refused(self):
        self.root.chmod(0o755)
        with self.assertRaises(ValueError): job.install_snapshot(*archive([regular()]), self.root)
        self.root.chmod(0o700); (self.root / 'existing').write_text('keep')
        with self.assertRaises(ValueError): job.install_snapshot(*archive([regular()]), self.root)
        self.assertEqual((self.root / 'existing').read_text(), 'keep')

    def test_bad_archive_creates_no_partial_files(self):
        with self.assertRaises(ValueError):
            job.install_snapshot(*archive([regular(), regular('../escape')]), self.root)
        self.assertEqual(list(self.root.iterdir()), [])

    def test_symlink_in_destination_ancestor_refused(self):
        (self.base / 'alias').symlink_to(self.root, target_is_directory=True)
        with self.assertRaises(OSError): job.install_snapshot(*archive([regular()]), self.base / 'alias')

    def test_good_report_and_trusted_exit(self):
        self.reports()
        result = job.collect_reports(self.root, self.output, 1)
        self.assertFalse(result['passed'])
        self.assertEqual(json.loads((self.output / 'manifest.json').read_text()), result)

    def test_pass_requires_required_classes(self):
        self.reports()
        result = job.collect_reports(self.root, self.output, 0, {'missing.Required'})
        self.assertFalse(result['passed'])
        self.assertEqual(result['missing_classes'], ['missing.Required'])

    def test_exact_test_count_is_in_persisted_manifest(self):
        self.reports()
        result = job.collect_reports(self.root, self.output, 0, expected_tests=82)
        self.assertFalse(result['passed'])
        self.assertEqual(result, json.loads((self.output / 'manifest.json').read_text()))

    def test_successful_collection(self):
        self.reports()
        result = job.collect_reports(self.root, self.output, 0, {'com.example.Tests'})
        self.assertTrue(result['passed'])
        self.assertTrue(result['not_isolation_approval'])
        self.assertEqual(result['timing']['test_count'], 1)
        self.assertEqual(result['timing']['slowest_classes'][0]['class_name'], 'com.example.Tests')

    def test_parent_symlink_refused(self):
        external = self.base / 'outside'; external.mkdir()
        (self.root / 'work').symlink_to(external, target_is_directory=True)
        with self.assertRaises(OSError): job.collect_reports(self.root, self.output, 0)
        self.assertEqual(list(self.output.iterdir()), [])

    def test_report_symlink_hardlink_and_fifo_refused(self):
        path = self.reports(); path.unlink()
        canary = self.base / 'canary'; canary.write_bytes(xml())
        for kind in ('symlink', 'hardlink', 'fifo'):
            if kind == 'symlink': path.symlink_to(canary)
            elif kind == 'hardlink': os.link(canary, path)
            else: os.mkfifo(path)
            with self.subTest(kind=kind), self.assertRaises((ValueError, OSError)):
                job.collect_reports(self.root, self.output, 0)
            path.unlink()
        self.assertEqual(canary.read_bytes(), xml())

    def test_mutation_during_read_refused(self):
        path = self.reports(); read = job.os.read
        changed = False
        def mutate(fd, size):
            nonlocal changed
            data = read(fd, size)
            if not changed:
                changed = True
                path.rename(path.with_suffix('.old'))
                path.symlink_to(self.base / 'nonexistent')
            return data
        with patch.object(job.os, 'read', side_effect=mutate):
            with self.assertRaises(ValueError): job.collect_reports(self.root, self.output, 0)
        self.assertEqual(list(self.output.iterdir()), [])

    def test_duplicate_report_class_refused(self):
        path = self.reports(); (path.parent / 'TEST-duplicate.xml').write_bytes(xml())
        with self.assertRaises(ValueError): job.collect_reports(self.root, self.output, 0)

    def test_report_size_count_and_total_limits(self):
        self.reports()
        for key, value in (('MAX_REPORT_FILE', 8), ('MAX_REPORT_TOTAL', 8), ('MAX_REPORTS', 0)):
            with patch.object(job, key, value), self.assertRaises(ValueError):
                job.collect_reports(self.root, self.output, 0)

    def test_malformed_xml_and_missing_report_refused(self):
        path = self.reports(b'<broken>')
        with self.assertRaises(job.ET.ParseError): job.collect_reports(self.root, self.output, 0)
        path.unlink()
        with self.assertRaises(ValueError): job.collect_reports(self.root, self.output, 0)

    def test_output_existing_file_not_overwritten(self):
        self.reports(); (self.output / 'manifest.json').write_text('keep')
        with self.assertRaises(ValueError): job.collect_reports(self.root, self.output, 0)
        self.assertEqual((self.output / 'manifest.json').read_text(), 'keep')


if __name__ == '__main__': unittest.main()
