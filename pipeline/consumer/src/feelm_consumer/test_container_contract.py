from pathlib import Path

CONSUMER_ROOT = Path(__file__).resolve().parents[2]


def test_dockerfile_pins_runtime_and_locked_install() -> None:
    dockerfile = (CONSUMER_ROOT / "Dockerfile").read_text(encoding="utf-8")

    assert "python:3.12.14-slim-bookworm@sha256:" in dockerfile
    assert "ghcr.io/astral-sh/uv:0.12.5" in dockerfile
    assert dockerfile.count("uv sync --locked --no-dev --no-editable") == 1
    assert "COPY pyproject.toml uv.lock .python-version ./" in dockerfile
    assert "COPY . ." not in dockerfile


def test_runtime_contains_hadoop_client_for_pyarrow() -> None:
    dockerfile = (CONSUMER_ROOT / "Dockerfile").read_text(encoding="utf-8")

    assert "apache/hadoop:3.4.3@sha256:" in dockerfile
    assert "eclipse-temurin:17.0.18_8-jre-jammy@sha256:" in dockerfile
    assert "COPY --from=java /opt/java/openjdk /opt/java/openjdk" in dockerfile
    assert 'PATH="/app/.venv/bin:/opt/java/openjdk/bin:/opt/hadoop/bin:$PATH"' in dockerfile
    assert "apt-get" not in dockerfile
    for setting in ("HADOOP_HOME", "JAVA_HOME", "ARROW_LIBHDFS_DIR", "CLASSPATH", "LD_LIBRARY_PATH"):
        assert setting in dockerfile
    assert "/opt/hadoop/lib/native" in dockerfile
    assert "/opt/hadoop/share/hadoop/common" in dockerfile
    assert "/opt/hadoop/share/hadoop/hdfs" in dockerfile
    assert "org.apache.hadoop.util.VersionInfo" in dockerfile
    assert "ctypes.CDLL('/opt/hadoop/lib/native/libhdfs.so')" in dockerfile


def test_runtime_is_non_root_and_execs_consumer_directly() -> None:
    dockerfile = (CONSUMER_ROOT / "Dockerfile").read_text(encoding="utf-8")

    assert "USER consumer" in dockerfile
    assert 'CMD ["/app/.venv/bin/feelm-foldin-consumer"]' in dockerfile
    assert "ENTRYPOINT [\"sh\"" not in dockerfile
    assert "CMD sh " not in dockerfile


def test_build_context_excludes_secrets_and_artifacts() -> None:
    patterns = {
        line.strip()
        for line in (CONSUMER_ROOT / ".dockerignore").read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.lstrip().startswith("#")
    }

    required = {
        ".env",
        ".env.*",
        ".venv",
        "**/__pycache__",
        "*.log",
        "*.npy",
        "*.npz",
        "*.parquet",
        "*.pkl",
    }
    assert required <= patterns
