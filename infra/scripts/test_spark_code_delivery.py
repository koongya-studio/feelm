from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[2]
DOCKERFILE = ROOT / "infra" / "spark" / "Dockerfile.code"
DOCKERIGNORE = ROOT / "infra" / "spark" / "Dockerfile.code.dockerignore"
RUNTIME_IMAGE_FILE = ROOT / "infra" / "spark" / "runtime-image.env"
BUILD_SCRIPT = ROOT / "infra" / "scripts" / "build-spark-code-image.sh"
DEPLOY_SCRIPT = ROOT / "infra" / "scripts" / "deploy-spark-code.sh"
STATE_SCRIPT = ROOT / "infra" / "scripts" / "spark-code-state.py"
RUNTIME_DEPLOYER = ROOT / "infra" / "scripts" / "deploy-distributed-runtime.py"
PIPELINE_CI = ROOT / ".gitlab" / "ci" / "pipeline.yml"
INFRA_CI = ROOT / ".gitlab" / "ci" / "infra.yml"
SPARK_README = ROOT / "infra" / "spark" / "README.md"


def job_block(text: str, name: str) -> str:
    match = re.search(
        r"(?ms)^" + re.escape(name) + r":\n(.*?)(?=^[.]?[A-Za-z0-9_-]+:\n|\Z)",
        text,
    )
    if match is None:
        raise AssertionError("Missing CI job: " + name)
    return match.group(1)


class SparkCodeImageContractTest(unittest.TestCase):
    def test_image_uses_required_runtime_digest_and_copies_job_code(self):
        self.assertTrue(DOCKERFILE.is_file(), "Missing Spark code image Dockerfile")
        dockerfile = DOCKERFILE.read_text(encoding="utf-8")

        for expected in (
            "ARG SPARK_RUNTIME_IMAGE",
            "FROM ${SPARK_RUNTIME_IMAGE}",
            "ARG SOURCE_REVISION",
            "org.opencontainers.image.revision=$SOURCE_REVISION",
            "io.feelm.spark.runtime-image=$SPARK_RUNTIME_IMAGE",
            "/opt/feelm/pipeline/spark/.source-revision",
            "ENV PYTHONPATH=/opt/feelm/pipeline/spark",
            "COPY --chown=spark:spark pipeline/spark/common/",
            "COPY --chown=spark:spark pipeline/spark/batch/",
            "COPY --chown=spark:spark pipeline/spark/jobs/",
            "COPY --chown=spark:spark pipeline/spark/pyproject.toml",
            "COPY --chown=spark:spark pipeline/spark/uv.lock",
        ):
            self.assertIn(expected, dockerfile)
        self.assertTrue(dockerfile.rstrip().endswith("USER spark"))

    def test_image_copy_allowlist_excludes_tests_secrets_and_other_services(self):
        self.assertTrue(DOCKERFILE.is_file(), "Missing Spark code image Dockerfile")
        self.assertTrue(DOCKERIGNORE.is_file(), "Missing Spark code image context allowlist")
        dockerfile = DOCKERFILE.read_text(encoding="utf-8")
        dockerignore = DOCKERIGNORE.read_text(encoding="utf-8")

        for forbidden in (
            "COPY . ",
            "COPY pipeline ",
            "pipeline/spark/tests",
            ".env",
            ".git",
            "backend/",
            "frontend/",
            "pipeline/consumer",
        ):
            self.assertNotIn(forbidden, dockerfile)
        self.assertTrue(dockerignore.startswith("**\n"))
        for expected in (
            "!pipeline/spark/common/**",
            "!pipeline/spark/batch/**",
            "!pipeline/spark/jobs/**",
            "!pipeline/spark/streaming/**",
            "!pipeline/spark/pyproject.toml",
            "!pipeline/spark/uv.lock",
        ):
            self.assertIn(expected, dockerignore)
        for forbidden in ("!.env", "!backend/", "!frontend/", "!pipeline/spark/tests/"):
            self.assertNotIn(forbidden, dockerignore)
        last_allow = dockerignore.rfind("!pipeline/spark/.python-version")
        for excluded in (
            "**/.env", "**/.env.*", "**/__pycache__/", "**/*.py[cod]",
            "**/.pytest_cache/", "**/.ruff_cache/", "**/tests/", "**/test_*.py",
        ):
            self.assertGreater(dockerignore.rfind(excluded), last_allow)


class SparkCodeBuildContractTest(unittest.TestCase):
    def test_runtime_image_digest_is_versioned_and_immutable(self):
        self.assertTrue(RUNTIME_IMAGE_FILE.is_file(), "Missing versioned runtime image digest")
        runtime_image = RUNTIME_IMAGE_FILE.read_text(encoding="utf-8").strip()

        self.assertRegex(
            runtime_image,
            r"^SPARK_RUNTIME_IMAGE=choec/feelm-spark-ml@sha256:[0-9a-f]{64}$",
        )
        self.assertNotIn(":develop-", runtime_image)

    def test_build_script_requires_digest_revision_and_emits_repo_digest(self):
        self.assertTrue(BUILD_SCRIPT.is_file(), "Missing Spark code image build script")
        script = BUILD_SCRIPT.read_text(encoding="utf-8")

        for expected in (
            "SPARK_RUNTIME_IMAGE",
            "SPARK_CODE_IMAGE",
            "SOURCE_REVISION",
            "SPARK_IMAGE_DIGEST",
            "@sha256:",
            "docker build",
            "--build-arg SPARK_RUNTIME_IMAGE=",
            "--build-arg SOURCE_REVISION=",
            "--file infra/spark/Dockerfile.code",
            "docker push",
            "RepoDigests",
            "SPARK_CODE_REPOSITORY",
            "matching_digests",
        ):
            self.assertIn(expected, script)
        self.assertRegex(script, r"SOURCE_REVISION.*[0-9a-f].*40")
        self.assertNotIn("latest", script.casefold())

    def test_two_node_deployment_uses_pending_state_and_compensating_settlement(self):
        self.assertTrue(DEPLOY_SCRIPT.is_file(), "Missing Spark deployment transaction script")
        self.assertTrue(STATE_SCRIPT.is_file(), "Missing atomic Spark deployment state tool")
        script = DEPLOY_SCRIPT.read_text(encoding="utf-8")
        for expected in (
            "preflight)", "initialize)", "apply)", "settle)", "prepare)", "resolve)",
            "spark-${NODE}-pending.json", "spark-code-state.py",
            "--spark-recovery-mode", "verify_running",
            "Interrupted Spark transaction detected; restoring last-known-good first",
        ):
            self.assertIn(expected, script)
        for forbidden in ("docker compose down", "down -v", "docker volume rm"):
            self.assertNotIn(forbidden, script)
        preflight = script[script.index("  preflight)"):script.index("  initialize)")]
        self.assertLess(
            preflight.index("ensure_previous_state"),
            preflight.index("--preflight-only"),
        )
        self.assertNotIn("--spark-idle-check-only", preflight)
        self.assertIn("approved_revision", script)
        self.assertNotIn("--allow-legacy-spark-image", script)
        self.assertNotIn(
            "--allow-legacy-spark-image",
            RUNTIME_DEPLOYER.read_text(encoding="utf-8"),
        )


class SparkCodeCiContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.pipeline = PIPELINE_CI.read_text(encoding="utf-8")
        cls.infra = INFRA_CI.read_text(encoding="utf-8")

    def test_spark_code_pipeline_is_main_only_and_change_filtered(self):
        main_push = (
            '$CI_COMMIT_BRANCH == "main" && '
            '$CI_COMMIT_REF_PROTECTED == "true" && '
            '$CI_PIPELINE_SOURCE == "push"'
        )
        expected_paths = (
            "pipeline/spark/**/*.py",
            "pipeline/spark/pyproject.toml",
            "pipeline/spark/uv.lock",
            "pipeline/spark/.python-version",
            "infra/spark/Dockerfile",
            "infra/spark/Dockerfile.code",
            "infra/spark/Dockerfile.code.dockerignore",
            "infra/spark/requirements-ml.txt",
            "infra/spark/runtime-image.env",
            "infra/compose/service.yml",
            "infra/compose/distributed.yml",
            "infra/scripts/build-spark-code-image.sh",
            "infra/scripts/deploy-spark-code.sh",
            "infra/scripts/spark-code-state.py",
            "infra/scripts/deploy-distributed-runtime.py",
            "infra/scripts/runtime-config-release.py",
            "infra/scripts/ci-deploy-current-commit.py",
            "infra/scripts/test_spark_code_delivery.py",
            "infra/scripts/test_spark_code_state.py",
            "infra/scripts/test_deploy_distributed_runtime.py",
            "infra/scripts/test_runtime_config_release.py",
            ".gitlab/ci/pipeline.yml",
            ".gitlab/ci/infra.yml",
        )

        test_job = job_block(self.pipeline, "spark-code-test")
        image_job = job_block(self.pipeline, "spark-code-image")
        for block in (test_job, image_job):
            self.assertIn(main_push, block)
            self.assertNotIn('$CI_COMMIT_BRANCH == "develop"', block)
            self.assertNotIn("merge_request_event", block)
            self.assertNotIn("when: manual", block)
            for path in expected_paths:
                self.assertIn("- " + path, block)

    def test_spark_code_image_uses_test_gate_and_digest_artifact(self):
        test_job = job_block(self.pipeline, "spark-code-test")
        image_job = job_block(self.pipeline, "spark-code-image")

        for expected in (
            "stage: test",
            "tags: [e106-shell]",
            "infra.scripts.test_spark_code_delivery",
            "infra.spark.test_spark_ml_runtime",
            "interruptible: true",
        ):
            self.assertIn(expected, test_job)
        for expected in (
            "stage: build",
            "tags: [e106-shell]",
            "needs: [spark-code-test]",
            'SPARK_RUNTIME_IMAGE',
            "spark-runtime-image.env",
            "build-spark-runtime-image.sh",
            "grep -c '^SPARK_RUNTIME_IMAGE='",
            "sed -n 's/^SPARK_RUNTIME_IMAGE=//p'",
            '$DOCKERHUB_NAMESPACE/feelm-spark:',
            'build-spark-code-image.sh',
            "reports:",
            "dotenv: spark-code-image.env",
            "interruptible: true",
        ):
            self.assertIn(expected, image_job)
        self.assertNotIn('test -n "$SPARK_RUNTIME_IMAGE"', image_job)
        self.assertNotIn("bash infra/scripts/deploy-distributed-runtime.py", image_job)

    def test_two_node_transaction_preflights_before_apply_and_settles_both_nodes(self):
        service_preflight = job_block(self.infra, "spark-code-preflight-service")
        distributed_preflight = job_block(self.infra, "spark-code-preflight-distributed")
        distributed_apply = job_block(self.infra, "spark-code-apply-distributed")
        service_apply = job_block(self.infra, "spark-code-apply-service")
        service_settle = job_block(self.infra, "spark-code-settle-service")
        distributed_settle = job_block(self.infra, "spark-code-settle-distributed")
        service_prepare = job_block(self.infra, "spark-code-prepare-resolution-service")
        distributed_prepare = job_block(self.infra, "spark-code-prepare-resolution-distributed")
        service_resolve = job_block(self.infra, "spark-code-resolve-service")
        distributed_resolve = job_block(self.infra, "spark-code-resolve-distributed")
        final_notify = job_block(self.infra, "spark-code-notify")

        self.assertIn("preflight service", service_preflight)
        self.assertIn("preflight distributed", distributed_preflight)
        self.assertIn("ci-deploy-current-commit.py", service_preflight)
        self.assertIn("ci-deploy-current-commit.py", distributed_preflight)
        self.assertIn('if [[ "$CI_JOB_STATUS" != success ]]', service_preflight)
        self.assertIn('if [[ "$CI_JOB_STATUS" != success ]]', distributed_preflight)
        self.assertIn("- job: spark-code-preflight-distributed", service_preflight)
        for name in ("spark-code-preflight-service", "spark-code-preflight-distributed"):
            self.assertIn("- job: " + name, distributed_apply)
        self.assertIn("apply distributed", distributed_apply)
        self.assertIn("ci-deploy-current-commit.py && bash", distributed_apply)
        self.assertIn("SPARK_DISTRIBUTED_RESULT", distributed_apply)
        self.assertIn("- job: spark-code-apply-distributed", service_apply)
        self.assertIn("apply service", service_apply)
        self.assertIn("ci-deploy-current-commit.py && bash", service_apply)
        self.assertIn("SPARK_SERVICE_RESULT", service_apply)
        for block, node in (
            (service_settle, "service"),
            (distributed_settle, "distributed"),
        ):
            self.assertIn("- job: spark-code-apply-distributed", block)
            self.assertIn("- job: spark-code-apply-service", block)
            self.assertIn("settle " + node, block)
            self.assertIn("SPARK_TRANSACTION_OUTCOME", block)
            self.assertIn("artifacts:", block)
        for block, node in (
            (service_prepare, "service"),
            (distributed_prepare, "distributed"),
        ):
            self.assertIn("prepare " + node, block)
            self.assertIn("spark-code-settle-service", block)
            self.assertIn("spark-code-settle-distributed", block)
            self.assertIn("manual_recovery", block)
        for block, node in (
            (service_resolve, "service"),
            (distributed_resolve, "distributed"),
        ):
            self.assertIn("resolve " + node, block)
            self.assertIn("spark-code-prepare-resolution-service", block)
            self.assertIn("spark-code-prepare-resolution-distributed", block)
            self.assertIn("manual_recovery", block)
        self.assertIn("SPARK_SERVICE_RESOLUTION", final_notify)
        self.assertIn("SPARK_DISTRIBUTED_RESOLUTION", final_notify)
        self.assertIn("auto_rollback", final_notify)
        self.assertIn("manual_recovery", final_notify)
        self.assertIn("notify-deployment.py spark-code", final_notify)
        operations = SPARK_README.read_text(encoding="utf-8")
        self.assertIn("distributed-runtime-spark", operations)
        self.assertIn("process_mode=oldest_first", operations)


if __name__ == "__main__":
    unittest.main()
