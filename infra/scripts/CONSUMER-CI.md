# Consumer CI policy

S15P21E106-486 adds a separate CI boundary for `pipeline/consumer`. The Consumer
project remains independent from `pipeline/spark`; neither archive nor test Job
may include the other project.

## Merge request validation

`consumer-mr-test` is created for a merge request to `develop` when Consumer
runtime code, tests, dependency manifests, container inputs, or its reviewed CI
adapter changes. The visible GitLab script is fixed to:

```bash
cd pipeline/consumer
uv sync --frozen
uv run ruff check .
uv run pytest -v
```

The isolated adapter must authenticate the current GitLab Job and merge request,
download only the exact commit archive, validate it with
`ci-consumer-mr-boundary.py`, and run the fixed commands in the reviewed Python
3.12 environment. It must not expose host Docker, deployment credentials,
artifacts, caches selected by the merge request, or arbitrary CI commands.

The boundary currently pins the reviewed `.python-version`, `pyproject.toml`,
`uv.lock`, and `Dockerfile` identities. A dependency or base-image change must
therefore update the reviewed runtime/cache and these identities in the same CI
policy change; an untrusted MR cannot select a new package source or image.

## Main image publication

`consumer-test` builds the Dockerfile `test` target on a protected `main` push.
That target installs the locked development environment and runs Ruff and pytest,
so the shared Runner does not need a host Python or uv installation.
Only after it succeeds may `consumer-image` build and push:

```text
$DOCKERHUB_NAMESPACE/feelm-foldin-consumer:$CI_COMMIT_SHA
```

The image Job does not publish `latest` and does not deploy. Server Secret
injection, selecting this immutable tag in `CONSUMER_IMAGE`, health/lag checks,
rollback, and Kafka-to-Redis E2E verification belong to S15P21E106-487.

## Server activation

The root configuration and shared adapter include the Consumer policy. Repository
merge alone does not prepare the fixed CI image or install the adapter on the
service server. From a reviewed checkout, first inspect the preparation plan,
then explicitly build and install the fixed image identity:

```bash
sudo python3 /usr/local/lib/feelm-ci222/gitlab/ci-consumer-mr-prepare.py
sudo python3 /usr/local/lib/feelm-ci222/gitlab/ci-consumer-mr-prepare.py \
  --source-root /path/to/reviewed/S15P21E106 --confirm-build
```

After reviewing the emitted image ID, install the root-owned Consumer config as
directed by `ci-consumer-mr-config.py`, then apply the reviewed adapter stage with
`ci-gitlab-adapter-update.py`. Validate a real MR success, failure, cancellation,
timeout, and cleanup before treating the route as operational.
