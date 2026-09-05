import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('port', Path(__file__).with_name('ci-offline-port-check.py'))
port = importlib.util.module_from_spec(spec)
spec.loader.exec_module(port)


class OfflinePortTests(unittest.TestCase):
    def test_target_rejection(self):
        for root, options in [('/var/lib/docker', ['name=rootless']),
                              ('/home/feelm-ci-test/.local/share/docker', ['name=rootless']),
                              (port.ROOT, [])]:
            with self.assertRaises(ValueError):
                port.validate_info({'DockerRootDir': root, 'SecurityOptions': options})
        port.validate_info({'DockerRootDir': port.ROOT, 'SecurityOptions': ['name=rootless']})

    def test_plan_does_not_probe(self):
        with patch('sys.argv', ['probe']), patch.object(port, 'run') as run:
            self.assertEqual(port.main(), 0)
            run.assert_not_called()

    def test_confirmation_required(self):
        with patch('sys.argv', ['probe', 'run']), patch.object(port, 'run') as run:
            with self.assertRaises(SystemExit):
                port.main()
            run.assert_not_called()

    def test_client_has_identity_and_token_checks_without_dns_setup(self):
        self.assertIn('verify_namespace_identity()', port.CLIENT)
        self.assertIn("response.read(128) != os.environ['PROBE_TOKEN'].encode()", port.CLIENT)
        self.assertNotIn('with private_dns()', port.CLIENT)
        self.assertNotIn('https://lab.ssafy.com', port.CLIENT)


if __name__ == '__main__':
    unittest.main()
