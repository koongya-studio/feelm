import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('custom_executor', HERE / 'ci-gitlab-custom-executor.py')
executor = importlib.util.module_from_spec(spec); spec.loader.exec_module(executor)


class Process:
    def __init__(self, code): self.code = code; self.stdin = self
    def write(self, value): self.value = value
    def close(self): pass
    def wait(self): return self.code
    def poll(self): return None
    def send_signal(self, _signal): pass


class ExecutorTests(unittest.TestCase):
    def files(self, directory):
        response = Path(directory) / 'response.json'; response.write_text(json.dumps({'id': 1}))
        script = Path(directory) / 'script'; script.write_text('must not run')
        response.chmod(0o600); script.chmod(0o600)
        return response, script

    def test_noop_never_executes_generated_script(self):
        with tempfile.TemporaryDirectory() as directory:
            response, script = self.files(directory)
            with patch.dict(os.environ, {'JOB_RESPONSE_FILE': str(response)}, clear=True), \
                 patch('sys.argv', ['executor', 'run', str(script), 'prepare_script']), \
                 patch.object(executor, 'execute') as execute:
                self.assertEqual(executor.main(), 0); execute.assert_not_called()

    def test_fixed_job_stages_invoke_root_command_and_map_failure(self):
        for stage in ('build_script', 'step_script'):
            with self.subTest(stage=stage), tempfile.TemporaryDirectory() as directory:
                response, script = self.files(directory); exit_file = Path(directory) / 'exit'
                environment = {'JOB_RESPONSE_FILE': str(response), 'BUILD_EXIT_CODE_FILE': str(exit_file),
                               'BUILD_FAILURE_EXIT_CODE': '17', 'SYSTEM_FAILURE_EXIT_CODE': '18'}
                with patch.dict(os.environ, environment, clear=True), \
                     patch('sys.argv', ['executor', 'run', str(script), stage]), \
                     patch.object(executor.subprocess, 'Popen', return_value=Process(2)) as popen:
                    self.assertEqual(executor.main(), 17)
                    self.assertEqual(exit_file.read_text(), '2\n')
                    self.assertEqual(popen.call_args.args[0], executor.ROOT_COMMAND)

    def test_unexpected_step_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            response, script = self.files(directory)
            with patch.dict(os.environ, {'JOB_RESPONSE_FILE': str(response)}, clear=True), \
                 patch('sys.argv', ['executor', 'run', str(script), 'step_release']):
                self.assertEqual(executor.main(), 1)


if __name__ == '__main__': unittest.main()
