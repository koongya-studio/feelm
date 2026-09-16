FROM apache/hadoop:3.4.3@sha256:127774dadab40ce84df7ac668a7a8c99945688b3fe336f1388f4477ca33e1529 AS hadoop


FROM eclipse-temurin:17.0.18_8-jre-jammy@sha256:e17d77fb030dd4b642dc078d048a5fb9efcb3676ee20305d905949105a6ccd5a AS java


FROM python:3.12.14-slim-bookworm@sha256:782412e85d0f0984994c290652577d4018aff08145c85b262bb63dc0c7522254 AS builder

COPY --from=ghcr.io/astral-sh/uv:0.12.5 /uv /uvx /bin/

ENV UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    UV_PYTHON_DOWNLOADS=0

WORKDIR /app

COPY pyproject.toml uv.lock .python-version ./
COPY src ./src

RUN uv sync --locked --no-dev --no-editable \
    && rm -rf /root/.cache/uv


FROM builder AS test

RUN uv sync --locked \
    && uv run ruff check . \
    && uv run pytest -v


FROM python:3.12.14-slim-bookworm@sha256:782412e85d0f0984994c290652577d4018aff08145c85b262bb63dc0c7522254 AS runtime

ENV ARROW_LIBHDFS_DIR=/opt/hadoop/lib/native \
    CLASSPATH=/opt/hadoop/etc/hadoop:/opt/hadoop/share/hadoop/common/*:/opt/hadoop/share/hadoop/common/lib/*:/opt/hadoop/share/hadoop/hdfs/*:/opt/hadoop/share/hadoop/hdfs/lib/* \
    HADOOP_HOME=/opt/hadoop \
    HOME=/tmp \
    JAVA_HOME=/opt/java/openjdk \
    LD_LIBRARY_PATH=/opt/hadoop/lib/native:/opt/java/openjdk/lib/server \
    PATH="/app/.venv/bin:/opt/java/openjdk/bin:/opt/hadoop/bin:$PATH" \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

RUN groupadd --system --gid 10001 consumer \
    && useradd --system --uid 10001 --gid consumer --home-dir /nonexistent --shell /usr/sbin/nologin consumer

WORKDIR /app

COPY --from=hadoop /opt/hadoop/etc/hadoop /opt/hadoop/etc/hadoop
COPY --from=hadoop /opt/hadoop/lib/native /opt/hadoop/lib/native
COPY --from=hadoop /opt/hadoop/share/hadoop/common /opt/hadoop/share/hadoop/common
COPY --from=hadoop /opt/hadoop/share/hadoop/hdfs /opt/hadoop/share/hadoop/hdfs
COPY --from=java /opt/java/openjdk /opt/java/openjdk
COPY --from=builder --chown=10001:10001 /app/.venv /app/.venv

RUN java -cp "$CLASSPATH" org.apache.hadoop.util.VersionInfo | grep -Fqx "Hadoop 3.4.3" \
    && /app/.venv/bin/python -c "import ctypes; ctypes.CDLL('/opt/hadoop/lib/native/libhdfs.so')"

USER consumer

CMD ["/app/.venv/bin/feelm-foldin-consumer"]
