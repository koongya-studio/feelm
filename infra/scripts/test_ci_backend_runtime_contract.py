import importlib.util
from pathlib import Path
import tempfile
import unittest


HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location(
    'runtime_contract', HERE / 'ci-backend-runtime-contract.py')
runtime = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime)


class BackendRuntimeContractTests(unittest.TestCase):
    def runtime_tree(self, root, suffix=b''):
        for name in runtime.FILES:
            path = root.joinpath(*name.split('/'))
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(name.encode() + suffix)
            path.chmod(0o444)

    def test_equal_runtime_trees_match(self):
        with tempfile.TemporaryDirectory() as directory:
            left = Path(directory) / 'left'
            right = Path(directory) / 'right'
            self.runtime_tree(left)
            self.runtime_tree(right)
            runtime.require_same(left, right)

    def test_changed_or_missing_runtime_file_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            left = Path(directory) / 'left'
            right = Path(directory) / 'right'
            self.runtime_tree(left)
            self.runtime_tree(right)
            changed = right.joinpath(*runtime.FILES[-1].split('/'))
            changed.chmod(0o644)
            changed.write_bytes(b'changed')
            changed.chmod(0o444)
            with self.assertRaisesRegex(RuntimeError, 'backend runtimes differ'):
                runtime.require_same(left, right)
            changed.chmod(0o644)
            changed.unlink()
            with self.assertRaises((FileNotFoundError, ValueError)):
                runtime.require_same(left, right)


if __name__ == '__main__':
    unittest.main()
