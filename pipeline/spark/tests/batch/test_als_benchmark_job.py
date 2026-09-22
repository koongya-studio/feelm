from unittest.mock import Mock

import pytest

from jobs.batch import als_benchmark as job


def test_invalid_run_is_rejected_before_spark_session(monkeypatch):
    create = Mock()
    monkeypatch.setattr(job, "create_spark_session", create)

    with pytest.raises(ValueError, match="expected count"):
        job.main(
            [
                "run",
                "--input-uri",
                "file:///tmp/input",
                "--input-manifest-uri",
                "file:///tmp/manifest.json",
                "--result-uri",
                "file:///tmp/result",
                "--input-id",
                "fixture",
                "--input-snapshot",
                "snapshot",
                "--expected-count",
                "0",
                "--worker-count",
                "1",
                "--implementation-revision",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "--sequence",
                "1",
            ]
        )

    create.assert_not_called()


def test_overlapping_prepare_paths_are_rejected_before_spark_session(monkeypatch):
    create = Mock()
    monkeypatch.setattr(job, "create_spark_session", create)

    with pytest.raises(ValueError, match="must not overlap"):
        job.main(
            [
                "prepare",
                "--source-uri",
                "hdfs://namenode/benchmark/source",
                "--output-uri",
                "hdfs://namenode/benchmark/source/sample",
                "--input-id",
                "fixture",
                "--source-snapshot",
                "snapshot",
                "--size",
                "10",
            ]
        )

    create.assert_not_called()


def test_prepare_dispatches_and_stops_session(monkeypatch, capsys):
    session = Mock()
    monkeypatch.setattr(job, "create_spark_session", Mock(return_value=session))
    prepare = Mock(return_value={"row_count": 10})
    monkeypatch.setattr(job, "prepare_input", prepare)

    job.main(
        [
            "prepare",
            "--source-uri",
            "file:///tmp/source",
            "--output-uri",
            "file:///tmp/output",
            "--input-id",
            "fixture-10",
            "--source-snapshot",
            "snapshot",
            "--size",
            "10",
        ]
    )

    prepare.assert_called_once()
    session.stop.assert_called_once()
    assert '"row_count": 10' in capsys.readouterr().out


def test_compare_reads_both_reports(monkeypatch):
    session = Mock()
    monkeypatch.setattr(job, "create_spark_session", Mock(return_value=session))
    read = Mock(side_effect=[{"one": 1}, {"two": 2}])
    compare = Mock(return_value={"speedup": 2.0})
    monkeypatch.setattr(job, "read_report", read)
    monkeypatch.setattr(job, "compare_results", compare)

    job.main(
        [
            "compare",
            "--single-result-uri",
            "hdfs://namenode/single",
            "--distributed-result-uri",
            "hdfs://namenode/distributed",
        ]
    )

    compare.assert_called_once_with({"one": 1}, {"two": 2})
    session.stop.assert_called_once()
