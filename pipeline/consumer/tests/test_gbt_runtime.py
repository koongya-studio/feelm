"""GBT must be installed in the standard image, not silently skipped."""

import importlib.metadata
import tomllib
from pathlib import Path

import xgboost


def test_standard_environment_has_exact_gbt_runtime():
    expected = {"numpy": "2.4.6", "pyarrow": "19.0.1", "scipy": "1.15.2", "xgboost-cpu": "2.1.4"}
    assert {name: importlib.metadata.version(name) for name in expected} == expected
    assert xgboost.__version__ == "2.1.4"
    root = Path(__file__).resolve().parents[1]
    dependencies = tomllib.loads((root / "pyproject.toml").read_text())["project"]["dependencies"]
    assert all(f"{name}=={version}" in dependencies for name, version in expected.items())
