from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
DISTRIBUTED = (ROOT / "infra/compose/distributed.yml").read_text(encoding="utf-8")
SERVICE = (ROOT / "infra/compose/service.yml").read_text(encoding="utf-8")
SPARK_DEFAULTS = (ROOT / "infra/spark/spark-defaults.conf").read_text(encoding="utf-8")


def test_kafka_ui_is_versioned_read_only_and_bound_to_private_interface():
    assert "image: ghcr.io/kafbat/kafka-ui:v1.5.0" in DISTRIBUTED
    assert "SERVER_ADDRESS: ${DISTRIBUTED_NODE_IP:?DISTRIBUTED_NODE_IP is required}" in DISTRIBUTED
    assert 'SERVER_PORT: "8082"' in DISTRIBUTED
    assert 'DYNAMIC_CONFIG_ENABLED: "false"' in DISTRIBUTED
    assert 'KAFKA_CLUSTERS_0_READONLY: "true"' in DISTRIBUTED
    assert "/tmp:size=64m,mode=1777" in DISTRIBUTED


def test_history_server_reads_persistent_hdfs_event_logs():
    assert "spark-history-server:" in DISTRIBUTED
    assert '"spark-history-server:${DISTRIBUTED_NODE_IP:?DISTRIBUTED_NODE_IP is required}"' in DISTRIBUTED
    assert "hdfs://hdfs-namenode:8020/spark-events" in DISTRIBUTED
    assert "-Dspark.history.ui.port=18080" in DISTRIBUTED
    assert "SPARK_LOCAL_IP: ${DISTRIBUTED_NODE_IP:?DISTRIBUTED_NODE_IP is required}" in DISTRIBUTED
    assert "spark.eventLog.enabled true" in SPARK_DEFAULTS
    assert "spark.eventLog.compress true" in SPARK_DEFAULTS
    assert "spark.ui.port 4040" in SPARK_DEFAULTS


def test_all_compose_spark_daemons_mount_shared_defaults():
    mount = "../spark/spark-defaults.conf:/opt/spark/conf/spark-defaults.conf:ro"
    assert DISTRIBUTED.count(mount) == 3
    assert SERVICE.count(mount) == 1


def test_history_directory_initialization_is_idempotent_and_writable():
    assert "echo > /dev/tcp/127.0.0.1/9870 && echo > /dev/tcp/127.0.0.1/8020" in DISTRIBUTED
    assert "hdfs dfsadmin -safemode wait" in DISTRIBUTED
    assert "hdfs dfs -mkdir -p /spark-events" in DISTRIBUTED
    assert "hdfs dfs -chmod 1777 /spark-events" in DISTRIBUTED
