from pathlib import Path

import pytest

import ci_test_groups


def _write_test(root: Path, relative: str) -> None:
    destination = root / relative
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text("def test_placeholder():\n    pass\n", encoding="utf-8")


def test_repository_files_are_partitioned_once() -> None:
    groups = ci_test_groups.partition_test_files()
    grouped = [path for paths in groups.values() for path in paths]

    assert len(grouped) == len(set(grouped))
    assert set(grouped) == ci_test_groups.discover_test_files()
    assert len(groups["unit"]) == 22


def test_unknown_test_defaults_to_serial_spark_group(tmp_path: Path) -> None:
    _write_test(tmp_path, "tests/common/test_known_unit.py")
    _write_test(tmp_path, "tests/batch/test_new_behavior.py")

    groups = ci_test_groups.partition_test_files(
        tmp_path, frozenset({"tests/common/test_known_unit.py"})
    )

    assert groups["unit"] == ("tests/common/test_known_unit.py",)
    assert groups["spark-other"] == ("tests/batch/test_new_behavior.py",)


def test_missing_declared_unit_file_is_rejected(tmp_path: Path) -> None:
    (tmp_path / "tests").mkdir()

    with pytest.raises(ValueError, match="unit test files are missing"):
        ci_test_groups.partition_test_files(tmp_path, frozenset({"tests/common/test_missing.py"}))


def test_verify_reports_every_group(capsys: pytest.CaptureFixture[str]) -> None:
    assert ci_test_groups.main(["verify"]) == 0

    output = capsys.readouterr().out
    assert f"CI_TEST_GROUPS_VERIFIED total={len(ci_test_groups.discover_test_files())}" in output
    for group in ci_test_groups.RUN_GROUPS:
        assert f"{group}=" in output


def test_run_passes_only_selected_group_to_pytest(monkeypatch: pytest.MonkeyPatch) -> None:
    calls = []
    monkeypatch.setattr(
        ci_test_groups.pytest, "main", lambda arguments: calls.append(arguments) or 0
    )

    assert ci_test_groups.main(["unit", "-q", "--durations=20"]) == 0

    assert calls and calls[0][:2] == ["-q", "--durations=20"]
    selected = {
        Path(path).relative_to(ci_test_groups.PROJECT_ROOT).as_posix() for path in calls[0][2:]
    }
    assert selected == ci_test_groups.UNIT_TEST_FILES


def test_run_reports_resource_metrics(monkeypatch: pytest.MonkeyPatch, capsys) -> None:
    usages = iter(((1.0, 2.0, 100, 3, 4), (2.5, 2.5, 120, 8, 11)))
    clocks = iter((10.0, 12.0))
    monkeypatch.setattr(ci_test_groups, "_usage", lambda: next(usages))
    monkeypatch.setattr(ci_test_groups.time, "perf_counter", lambda: next(clocks))
    monkeypatch.setattr(ci_test_groups.pytest, "main", lambda arguments: 0)

    assert ci_test_groups.run_group("unit", ("-q",), ("test_example.py",)) == 0

    output = capsys.readouterr().out
    assert "CI_TEST_GROUP_STARTED group=unit files=1" in output
    assert "CI_TEST_GROUP_METRICS group=unit wall_seconds=2.000" in output
    assert "user_seconds=1.500" in output
    assert "max_rss_kb=120" in output
    assert "output_blocks=7" in output
