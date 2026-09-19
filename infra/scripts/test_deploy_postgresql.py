import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
DEPLOY_SCRIPT = ROOT / "infra" / "scripts" / "deploy-postgresql.sh"
VERIFY_SCRIPT = ROOT / "infra" / "scripts" / "verify-postgresql-deployment.sh"
BASH = os.environ.get("TEST_BASH", "bash")
CANDIDATE = "pgvector/pgvector:candidate"
PREVIOUS = "pgvector/pgvector:previous"
VOLUME = "compose_postgres_data"


class DeployPostgresqlTests(unittest.TestCase):
    @staticmethod
    def _bash_path(path):
        value = Path(path).as_posix()
        if os.name == "nt" and len(value) >= 3 and value[1:3] == ":/":
            return f"/{value[0].lower()}/{value[3:]}"
        return value

    def setUp(self):
        self.temp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.temp, ignore_errors=True)
        self.bin = self.temp / "bin"
        self.bin.mkdir()
        self.backups = self.temp / "backups"
        self.backups.mkdir()
        self.events = self.temp / "events"
        self.image = self.temp / "image"
        self.image.write_text(PREVIOUS)
        self.volume = self.temp / "volume"
        self.volume.write_text(VOLUME)
        self.health_count = self.temp / "health-count"
        self.runtime_count = self.temp / "runtime-count"
        self._write_fake_docker()
        self._write_fake_python()
        self.runtime_verify = self._write_helper(
            "runtime-verify.sh",
            'count=0; test -f "$RUNTIME_COUNT" && count=$(cat "$RUNTIME_COUNT"); '
            'count=$((count + 1)); printf "%s" "$count" > "$RUNTIME_COUNT"; '
            'if test "$count" -eq 1; then exit "$RUNTIME_VERIFY_EXIT"; fi; '
            'exit "$ROLLBACK_VERIFY_EXIT"',
        )

    def _write_helper(self, name, body):
        path = self.bin / name
        path.write_text("#!/usr/bin/env bash\nset -eu\n" + body + "\n")
        path.chmod(0o755)
        return path

    def _write_fake_python(self):
        self._write_helper("fake-python", f"cat >/dev/null; printf '%s\\n' '{CANDIDATE}'")

    def _write_fake_docker(self):
        path = self.bin / "docker"
        path.write_text(
            r'''#!/usr/bin/env bash
set -eu
printf '%s\n' "$*" >>"$FAKE_EVENTS"
if [[ "$1" == "inspect" ]]; then
  target="${*: -1}"
  if [[ "$*" == *'.Config.Image'* ]]; then
    if [[ "$target" == "feelm-backend" ]]; then
      printf '%s\n' 'registry.example/backend:current'
    else
      cat "$FAKE_IMAGE"
    fi
  elif [[ "$*" == *'.Mounts'* ]]; then
    cat "$FAKE_VOLUME"
  elif [[ "$*" == *'.State.Health'* ]]; then
    count=0
    test -f "$HEALTH_COUNT" && count=$(cat "$HEALTH_COUNT")
    count=$((count + 1))
    printf '%s' "$count" >"$HEALTH_COUNT"
    if [[ "${FORCE_HEALTH_STATUS:-}" == "starting" ]]; then
      printf '%s\n' starting
    elif ((count <= ${STARTING_HEALTH_CHECKS:-0})); then
      printf '%s\n' starting
    else
      printf '%s\n' healthy
    fi
  fi
  exit 0
fi
if [[ "$1" == "compose" ]]; then
  if [[ "$*" == *'config --format json'* ]]; then
    printf '%s\n' '{}'
  elif [[ "$*" == *'config --quiet'* ]]; then
    if [[ "$*" == *'previous-service.yml'* && "${FAIL_PREVIOUS_CONFIG:-0}" == "1" ]]; then
      exit 1
    fi
    exit 0
  elif [[ "$*" == *'pull --quiet postgres'* ]]; then
    exit 0
  elif [[ "$*" == *'up -d --no-deps --force-recreate postgres'* ]]; then
    if [[ "$POSTGRES_DEPLOY_IMAGE" == *candidate* && "${FAIL_CANDIDATE_UP:-0}" == "1" ]]; then
      exit 1
    fi
    if [[ "$POSTGRES_DEPLOY_IMAGE" == *previous* && "${FAIL_ROLLBACK_UP:-0}" == "1" ]]; then
      exit 1
    fi
    printf '%s' "$POSTGRES_DEPLOY_IMAGE" >"$FAKE_IMAGE"
    printf '%s' "${REPLACEMENT_VOLUME:-compose_postgres_data}" >"$FAKE_VOLUME"
    printf '0' >"$HEALTH_COUNT"
  fi
  exit 0
fi
if [[ "$1" == "run" ]]; then
  printf '%s\n' 'postgres (PostgreSQL) 17.6'
  exit 0
fi
if [[ "$1" == "exec" ]]; then
  if [[ "$*" == *'SHOW server_version_num'* ]]; then
    printf '%s\n' 170006
  elif [[ "$*" == *'pg_dumpall --globals-only'* ]]; then
    [[ "${FAIL_BACKUP:-0}" != "1" ]] || exit 1
    printf '%s\n' 'CREATE ROLE feelm;'
  elif [[ "$*" == *'pg_dump -Fc'* ]]; then
    [[ "${FAIL_BACKUP:-0}" != "1" ]] || exit 1
    printf '%s\n' 'PGDMP fake archive'
  elif [[ "$*" == *'pg_restore --list'* ]]; then
    cat >/dev/null
    [[ "${FAIL_RESTORE_CHECK:-0}" != "1" ]]
  fi
  exit 0
fi
exit 2
'''
        )
        path.chmod(0o755)

    def run_deploy(self, revision="abcdef1234567890", **overrides):
        environment = os.environ.copy()
        environment.update(
            {
                "PATH": self._bash_path(self.bin) + ":" + environment["PATH"],
                "FAKE_EVENTS": self._bash_path(self.events),
                "FAKE_IMAGE": self._bash_path(self.image),
                "FAKE_VOLUME": self._bash_path(self.volume),
                "HEALTH_COUNT": self._bash_path(self.health_count),
                "RUNTIME_COUNT": self._bash_path(self.runtime_count),
                "POSTGRES_BACKUP_ROOT": self._bash_path(self.backups),
                "POSTGRES_PREVIOUS_COMPOSE_FILE": self._bash_path(
                    ROOT / "infra" / "compose" / "service.yml"
                ),
                "POSTGRES_RUNTIME_VERIFY_SCRIPT": self._bash_path(self.runtime_verify),
                "PYTHON_BIN": self._bash_path(self.bin / "fake-python"),
                "RUNTIME_VERIFY_EXIT": "0",
                "ROLLBACK_VERIFY_EXIT": "0",
                "DEPLOY_HEALTH_INTERVAL_SECONDS": "0",
            }
        )
        environment.update({key: str(value) for key, value in overrides.items()})
        return subprocess.run(
            [BASH, self._bash_path(DEPLOY_SCRIPT), revision],
            cwd=ROOT,
            env=environment,
            text=True,
            encoding="utf-8",
            errors="replace",
            capture_output=True,
        )

    def test_success_backs_up_and_recreates_only_postgresql_with_same_volume(self):
        result = self.run_deploy()
        self.assertEqual(result.returncode, 0, result.stderr)
        backup = self.backups / "abcdef1234567890"
        self.assertTrue((backup / "database.dump").is_file())
        self.assertTrue((backup / "globals.sql").is_file())
        self.assertTrue((backup / "previous-service.yml").is_file())
        self.assertTrue((backup / "candidate-service.yml").is_file())
        self.assertIn(f"previous_image={PREVIOUS}", (backup / "manifest.txt").read_text())
        self.assertEqual(self.image.read_text(), CANDIDATE)
        self.assertEqual(self.volume.read_text(), VOLUME)
        events = self.events.read_text()
        self.assertIn("pull --quiet postgres", events)
        self.assertIn("up -d --no-deps --force-recreate postgres", events)
        self.assertNotIn(" down", events)
        self.assertNotIn("volume rm", events)

    def test_backup_failure_stops_before_recreate(self):
        result = self.run_deploy(FAIL_BACKUP=1)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.image.read_text(), PREVIOUS)
        self.assertNotIn("up -d", self.events.read_text())

    def test_restore_readiness_failure_stops_before_recreate(self):
        result = self.run_deploy(FAIL_RESTORE_CHECK=1)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.image.read_text(), PREVIOUS)
        self.assertNotIn("up -d", self.events.read_text())

    def test_runtime_failure_restores_previous_image_and_volume(self):
        result = self.run_deploy(RUNTIME_VERIFY_EXIT=1)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.image.read_text(), PREVIOUS)
        self.assertEqual(self.volume.read_text(), VOLUME)
        self.assertIn("previous image was restored", result.stderr)
        previous_compose = self.backups / "abcdef1234567890" / "previous-service.yml"
        self.assertIn(
            self._bash_path(previous_compose),
            self.events.read_text(),
        )

    def test_volume_change_restores_previous_image(self):
        result = self.run_deploy(REPLACEMENT_VOLUME="unexpected_volume")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.image.read_text(), PREVIOUS)
        self.assertIn("did not preserve the original named volume", result.stderr)

    def test_rollback_failure_requires_manual_recovery_and_keeps_backup(self):
        result = self.run_deploy(RUNTIME_VERIFY_EXIT=1, FAIL_ROLLBACK_UP=1)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("manual recovery is required", result.stderr)
        self.assertTrue((self.backups / "abcdef1234567890" / "database.dump").is_file())

    def test_invalid_revision_fails_before_docker(self):
        result = self.run_deploy(revision="not-a-revision")
        self.assertEqual(result.returncode, 2)
        self.assertFalse(self.events.exists())

    def test_missing_previous_compose_stops_before_backup_or_recreate(self):
        result = self.run_deploy(
            POSTGRES_PREVIOUS_COMPOSE_FILE=self._bash_path(self.temp / "missing.yml")
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Previous PostgreSQL Compose configuration is unavailable", result.stderr)
        self.assertNotIn("pg_dump", self.events.read_text())
        self.assertNotIn("up -d", self.events.read_text())

    def test_unusable_previous_compose_stops_before_backup_or_recreate(self):
        result = self.run_deploy(FAIL_PREVIOUS_CONFIG=1)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("not usable for rollback", result.stderr)
        self.assertNotIn("pg_dump", self.events.read_text())
        self.assertNotIn("up -d", self.events.read_text())


class VerifyPostgresqlDeploymentTests(unittest.TestCase):
    @staticmethod
    def _bash_path(path):
        return DeployPostgresqlTests._bash_path(path)

    def setUp(self):
        self.temp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.temp, ignore_errors=True)
        self.bin = self.temp / "bin"
        self.bin.mkdir()
        docker = self.bin / "docker"
        docker.write_text(
            r'''#!/usr/bin/env bash
set -eu
if [[ "$1" == "inspect" ]]; then
  target="${*: -1}"
  if [[ "$target" == "feelm-postgres" ]]; then
    printf '%s\n' "${POSTGRES_HEALTH:-healthy}"
  else
    printf '%s\n' "${BACKEND_HEALTH:-healthy}"
  fi
elif [[ "$*" == *'SELECT 1;'* ]]; then
  printf '%s\n' "${POSTGRES_RESULT:-1}"
elif [[ "$*" == *'/actuator/health'* ]]; then
  printf '%s\n' "${BACKEND_ACTUATOR:-{\"status\":\"UP\"}}"
else
  exit 2
fi
'''
        )
        docker.chmod(0o755)

    def run_verify(self, **overrides):
        environment = os.environ.copy()
        environment["PATH"] = self._bash_path(self.bin) + ":" + environment["PATH"]
        environment.update({key: str(value) for key, value in overrides.items()})
        return subprocess.run(
            [BASH, self._bash_path(VERIFY_SCRIPT)],
            cwd=ROOT,
            env=environment,
            text=True,
            encoding="utf-8",
            errors="replace",
            capture_output=True,
        )

    def test_healthy_postgresql_and_backend_pass(self):
        result = self.run_verify()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("connection verification passed", result.stdout)

    def test_postgresql_sql_failure_is_rejected(self):
        result = self.run_verify(POSTGRES_RESULT="0")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("SELECT 1 verification failed", result.stderr)

    def test_backend_health_failure_is_rejected(self):
        result = self.run_verify(BACKEND_HEALTH="unhealthy")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("feelm-backend is not healthy", result.stderr)

    def test_backend_actuator_failure_is_rejected(self):
        result = self.run_verify(BACKEND_ACTUATOR='{"status":"DOWN"}')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("actuator health did not confirm", result.stderr)


if __name__ == "__main__":
    unittest.main()
