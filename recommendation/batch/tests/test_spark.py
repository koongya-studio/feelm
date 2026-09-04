from unittest.mock import MagicMock, patch

from common.spark import create_spark_session


@patch("common.spark.SparkSession")
def test_submit_master_is_preserved_when_job_master_is_not_set(spark_session: MagicMock) -> None:
    builder = spark_session.builder
    builder.appName.return_value = builder
    builder.getOrCreate.return_value = session = object()

    assert create_spark_session(None, "test-app") is session
    builder.master.assert_not_called()


@patch("common.spark.SparkSession")
def test_explicit_job_master_is_applied(spark_session: MagicMock) -> None:
    builder = spark_session.builder
    builder.master.return_value = builder
    builder.appName.return_value = builder

    create_spark_session("local[1]", "test-app")

    builder.master.assert_called_once_with("local[1]")
