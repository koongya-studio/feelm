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

S15P21E106-644 pins the v26 CPU runtime in the existing Consumer project and
updates the three reviewed manifest/lock/Dockerfile hashes together. The fixed
Python/uv MR image and package-source allowlist are unchanged. An infrastructure
operator must review and install the updated root-owned boundary before the GBT
MR snapshot can pass; merging this file does not update the installed adapter.
Verify the frozen sync and GBT tests in that isolated environment without
disabling the boundary checks. See `pipeline/consumer/GBT-644.md` for the
separate runtime activation and whole-release rollback boundary.

## Main image publication

`consumer-test` builds the Dockerfile `test` target on a protected `main` push.
That target installs the locked development environment and runs Ruff and pytest,
so the shared Runner does not need a host Python or uv installation.
Only after it succeeds may `consumer-image` build and push:

```text
$DOCKERHUB_NAMESPACE/feelm-foldin-consumer:$CI_COMMIT_SHA
```

The image Job does not publish `latest`. It exports the Registry digest returned
for that SHA tag, and deployment uses the digest rather than resolving the tag
again. S15P21E106-487 adds `deploy-consumer`
after this Job: it injects the reviewed environment through a protected File
variable, selects the immutable digest, verifies process and Kafka group
state, and restores the previous image when verification fails. The operational
Kafka-to-Redis check remains an explicit post-deployment action because it needs
an approved test user and a real rating event.

## Main deployment

`deploy-consumer` runs only for a protected `main` push and uses a protected,
project-locked Shell Runner on the distributed node with tag
`e106-distributed`. It serializes deployments with the
`distributed-consumer` resource group and rejects a stale pipeline immediately
before changing the container.

The complete Compose dotenv is supplied as the protected, File-type
`CONSUMER_COMPOSE_ENV_FILE` variable scoped to the `distributed` environment.
The Job must never print this file. Docker credentials use a Job-specific
`DOCKER_CONFIG` that is logged out and removed in `after_script`. The host keeps
the last verified digest in a Runner-only deployment state directory so a
runner failure and retry cannot redefine an unverified candidate as the rollback
target. Runner registration, GitLab variable setup,
the E2E procedure, and rollback commands are documented in `infra/README.md`.

Before enabling the deploy Job, initialize that state exactly once from the
already verified running Consumer. Automatic deployment deliberately refuses to
infer a rollback target when the state file is absent:

```bash
sudo -u <runner-user> env \
  COMPOSE_ENV_FILE=/path/to/approved/infra.env \
  CONSUMER_DEPLOY_STATE_FILE=/var/lib/feelm-deploy/consumer-last-known-good \
  bash infra/scripts/deploy-consumer.sh --initialize-state
```

Initialization succeeds only when the running container has zero restarts and
one Consumer ID owns every configured topic partition. Re-running it never
silently replaces a previously recorded image with a different running image.

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
