from pathlib import Path
import re
import unittest


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
SPARK_DIR = REPOSITORY_ROOT / "infra" / "spark"
EXPECTED_IMAGE = "${SPARK_IMAGE:-feelm-spark-ml:4.1.3-numpy2.2.6}"
EXPECTED_BUILD = "context: ../spark\n      dockerfile: Dockerfile"
EXPECTED_NUMPY_VERSION = "2.2.6"
EXPECTED_NUMPY_HASH = "fc7b73d02efb0e18c000e9ad8b83480dfcd5dfd11065997ed4c6747470ae8915"
EXPECTED_REDIS_VERSION = "5.3.1"
EXPECTED_REDIS_HASH = "dc1909bd24669cc31b5f67a039700b16ec30571096c5f1f0d9d2324bff31af97"


class SparkMlRuntimeTests(unittest.TestCase):
    def test_numpy_requirement_matches_pipeline_lock(self):
        requirement = (SPARK_DIR / "requirements-ml.txt").read_text(encoding="utf-8")
        lock = (REPOSITORY_ROOT / "pipeline" / "spark" / "uv.lock").read_text(
            encoding="utf-8"
        )

        self.assertIn(f"numpy-{EXPECTED_NUMPY_VERSION}-cp310-cp310-manylinux", requirement)
        self.assertIn(f"--hash=sha256:{EXPECTED_NUMPY_HASH}", requirement)
        self.assertRegex(
            lock,
            re.compile(
                rf'numpy-{re.escape(EXPECTED_NUMPY_VERSION)}-cp310-cp310-manylinux[^\n]+'
                rf'hash = "sha256:{EXPECTED_NUMPY_HASH}"'
            ),
        )

    def test_dockerfile_preserves_spark_runtime_and_non_root_user(self):
        dockerfile = (SPARK_DIR / "Dockerfile").read_text(encoding="utf-8")

        self.assertIn(
            "apache/spark:4.1.3-scala2.13-java21-python3-ubuntu@sha256:", dockerfile
        )
        self.assertIn("--require-hashes", dockerfile)
        self.assertIn("requirements-ml.txt", dockerfile)
        self.assertTrue(dockerfile.rstrip().endswith("USER spark"))

    def test_redis_requirement_is_pinned_in_image_without_changing_pipeline_lock(self):
        requirement = (SPARK_DIR / "requirements-ml.txt").read_text(encoding="utf-8")
        dockerfile = (SPARK_DIR / "Dockerfile").read_text(encoding="utf-8")
        lock = (REPOSITORY_ROOT / "pipeline" / "spark" / "uv.lock").read_text(
            encoding="utf-8"
        )

        self.assertIn(f"redis-{EXPECTED_REDIS_VERSION}-py3-none-any.whl", requirement)
        self.assertIn(f"--hash=sha256:{EXPECTED_REDIS_HASH}", requirement)
        self.assertIn(f'redis.__version__ == "{EXPECTED_REDIS_VERSION}"', dockerfile)
        self.assertNotRegex(lock, r'(?m)^name = "redis"$')

    def test_all_spark_roles_build_the_same_local_image(self):
        service = (REPOSITORY_ROOT / "infra" / "compose" / "service.yml").read_text(
            encoding="utf-8"
        )
        distributed = (
            REPOSITORY_ROOT / "infra" / "compose" / "distributed.yml"
        ).read_text(encoding="utf-8")

        for compose, role in (
            (service, "spark-worker-1"),
            (distributed, "spark-master"),
            (distributed, "spark-worker-2"),
        ):
            role_block = re.search(
                rf"(?ms)^  {re.escape(role)}:\n(?P<body>.*?)(?=^  [a-zA-Z0-9_-]+:|^volumes:)",
                compose,
            )
            self.assertIsNotNone(role_block, role)
            body = role_block.group("body")
            self.assertIn(f"    image: {EXPECTED_IMAGE}\n", body)
            self.assertIn(EXPECTED_BUILD, body)

    def test_example_environment_declares_local_spark_image(self):
        example = (REPOSITORY_ROOT / "infra" / ".env.example").read_text(
            encoding="utf-8"
        )
        self.assertRegex(
            example,
            re.compile(
                r"^SPARK_IMAGE=feelm-spark-ml:4\.1\.3-numpy2\.2\.6$",
                re.MULTILINE,
            ),
        )


if __name__ == "__main__":
    unittest.main()
