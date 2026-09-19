from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[2]
BACKEND_CI = ROOT / ".gitlab" / "ci" / "backend.yml"
INFRA_CI = ROOT / ".gitlab" / "ci" / "infra.yml"
SERVICE_COMPOSE = ROOT / "infra" / "compose" / "service.yml"
DEPLOY_SCRIPT = ROOT / "infra" / "scripts" / "deploy-postgresql.sh"
VERIFY_SCRIPT = ROOT / "infra" / "scripts" / "verify-postgresql-deployment.sh"
INFRA_TEST_RUNNER = ROOT / "infra" / "scripts" / "run-infra-config-tests.py"


def job_block(text, name):
    match = re.search(
        r"(?ms)^" + re.escape(name) + r":\n(.*?)(?=^[.]?[A-Za-z0-9_-]+:\n|\Z)",
        text,
    )
    if match is None:
        raise AssertionError("Missing CI job: " + name)
    return match.group(1)


class PostgresqlCdConfigTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.ci = BACKEND_CI.read_text(encoding="utf-8")
        cls.infra_ci = INFRA_CI.read_text(encoding="utf-8")
        cls.compose = SERVICE_COMPOSE.read_text(encoding="utf-8")
        cls.deploy = DEPLOY_SCRIPT.read_text(encoding="utf-8")
        cls.verify = VERIFY_SCRIPT.read_text(encoding="utf-8")
        cls.infra_test_runner = INFRA_TEST_RUNNER.read_text(encoding="utf-8")

    def test_merge_request_configuration_check_uses_reviewed_infra_runner(self):
        block = job_block(self.infra_ci, "infra-config-test")
        for expected in (
            "stage: test",
            "tags: [e106-mr-isolated]",
            "dependencies: []",
            "GIT_STRATEGY: none",
            "python3 -I -B infra/scripts/run-infra-config-tests.py",
            'CI_PIPELINE_SOURCE == "merge_request_event"',
            'CI_MERGE_REQUEST_TARGET_BRANCH_NAME == "develop"',
            "resource_group: infra-config-test",
            "interruptible: true",
        ):
            self.assertIn(expected, block)
        self.assertIn("- infra/compose/**/*", block)
        self.assertIn("- infra/scripts/*postgresql*", block)
        self.assertNotIn("tags: [e106-shell]", block)
        self.assertNotIn("when: manual", block)
        self.assertIn(
            '"infra.scripts.test_postgresql_cd_config"', self.infra_test_runner
        )
        self.assertIn('"infra.scripts.test_deploy_postgresql"', self.infra_test_runner)

    def test_deployment_is_manual_protected_main_only(self):
        block = job_block(self.ci, "deploy-postgresql")
        for expected in (
            "stage: deploy",
            "tags: [e106-shell]",
            "needs: [ci-variable-preflight]",
            "resource_group: service-and-backend-cache",
            "name: service",
            "ci-deploy-current-commit.py --branch main",
            'deploy-postgresql.sh "$CI_COMMIT_SHA"',
            'notify-deployment.py postgresql "$CI_JOB_STATUS"',
            'CI_COMMIT_BRANCH == "main"',
            'CI_COMMIT_REF_PROTECTED == "true"',
            'CI_PIPELINE_SOURCE == "push"',
            "when: manual",
            "allow_failure: false",
            "interruptible: false",
            "timeout: 30m",
        ):
            self.assertIn(expected, block)
        for forbidden in (
            'CI_COMMIT_BRANCH == "develop"',
            "merge_request_event",
            "retry:",
            "allow_failure: true",
            "docker compose down",
            "down -v",
        ):
            self.assertNotIn(forbidden, block)

    def test_compose_preserves_named_volume_and_health_dependency(self):
        postgres = re.search(
            r"(?ms)^  postgres:\n(.*?)(?=^  [a-zA-Z0-9_-]+:\n)", self.compose
        )
        backend = re.search(
            r"(?ms)^  backend:\n(.*?)(?=^  [a-zA-Z0-9_-]+:\n)", self.compose
        )
        self.assertIsNotNone(postgres)
        self.assertIsNotNone(backend)
        postgres_block = postgres.group(1)
        backend_block = backend.group(1)
        self.assertRegex(postgres_block, r"image: .*pg17.*@sha256:[0-9a-f]{64}")
        self.assertIn("postgres_data:/var/lib/postgresql/data", postgres_block)
        self.assertIn("pg_isready", postgres_block)
        self.assertIn("postgres:", backend_block)
        self.assertIn("condition: service_healthy", backend_block)
        self.assertRegex(self.compose, r"(?m)^  postgres_data:$")

    def test_deploy_script_requires_backup_restore_check_and_same_volume(self):
        for expected in (
            "pg_dump -Fc",
            "pg_dumpall --globals-only",
            "pg_restore --list",
            "previous-service.yml",
            "CI_COMMIT_BEFORE_SHA",
            "postgres_volume",
            "POSTGRES_VOLUME",
            "--no-deps --force-recreate postgres",
            "restore_previous",
            'recreate_postgresql "${PREVIOUS_IMAGE}" "${BACKUP_DIR}/previous-service.yml"',
            "PostgreSQL major version changes require",
        ):
            self.assertIn(expected, self.deploy)
        for forbidden in ("down -v", "docker volume rm", "docker compose down"):
            self.assertNotIn(forbidden, self.deploy)

    def test_runtime_verification_covers_postgresql_and_backend(self):
        for expected in (
            "feelm-postgres",
            "SELECT 1;",
            "feelm-backend",
            "/actuator/health",
            '"status":"UP"',
        ):
            self.assertIn(expected, self.verify)


if __name__ == "__main__":
    unittest.main()
