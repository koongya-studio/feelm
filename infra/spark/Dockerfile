FROM apache/spark:4.1.3-scala2.13-java21-python3-ubuntu@sha256:bf9d035a7c32a8ca46aa58d6348182ffd7d2dff6409206ecfbb3915ff1fef211

USER root

COPY requirements-ml.txt /opt/spark/requirements-ml.txt

RUN python3 -m pip install \
      --no-cache-dir \
      --require-hashes \
      --requirement /opt/spark/requirements-ml.txt \
    && python3 -c 'import numpy; assert numpy.__version__ == "2.2.6"'

USER spark
