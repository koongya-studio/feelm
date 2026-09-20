#!/usr/bin/env python3
"""Run the reviewed infra configuration contract test group."""

import sys
import unittest
import importlib
from pathlib import Path


HERE = Path(__file__).resolve().parent
TESTS = (
    "infra.scripts.test_consumer_distributed_deployment",
    "infra.scripts.test_kafka_monitoring",
    "infra.scripts.test_grafana_access_policy",
    "infra.scripts.test_grafana_alerting",
    "infra.scripts.test_loki_logging",
    "infra.scripts.test_mattermost_alert_relay",
    "infra.scripts.test_node_exporter_monitoring",
    "infra.scripts.test_verify_kafka_monitoring",
    "infra.scripts.test_deploy_distributed_runtime",
    "infra.scripts.test_spark_code_delivery",
    "infra.scripts.test_spark_runtime_delivery",
    "infra.scripts.test_spark_code_state",
    "infra.scripts.test_runtime_config_release",
    "infra.scripts.test_deploy_consumer",
    "infra.scripts.test_verify_consumer_e2e",
    "infra.scripts.test_notify_deployment",
    "infra.scripts.test_ci_required_variable_preflight",
    "infra.scripts.test_ci_deploy_current_commit",
    "infra.scripts.test_deploy_monitoring",
    "infra.scripts.test_verify_monitoring_deployment",
    "infra.scripts.test_postgresql_cd_config",
    "infra.scripts.test_deploy_postgresql",
    "infra.scripts.test_distributed_auxiliary_deployment",
    "infra.scripts.test_verify_distributed_observability",
    "infra.scripts.test_verify_spark_history",
    "infra.scripts.test_bootstrap_reconcile",
    "infra.scripts.test_ci_config",
    "infra.scripts.test_ci_backend_mr_paths",
    "infra.scripts.test_ci_infra_mr",
    "infra.scripts.test_ci_gitlab_job_adapter.AdapterTests."
    "test_context_accepts_only_fixed_job_and_script",
    "infra.scripts.test_ci_gitlab_job_adapter.AdapterTests."
    "test_infra_job_routes_only_validated_source_to_infra_runner",
    "infra.scripts.test_ci_gitlab_job_adapter.AdapterTests."
    "test_owner_stop_accepts_only_owned_unit_name",
    "infra.scripts.test_ci_gitlab_adapter_update",
    "infra.scripts.test_ci_server_pilot_stage",
)


def main():
    sys.path.insert(0, str(HERE.parents[1]))
    suite = unittest.defaultTestLoader.loadTestsFromNames(TESTS)
    if not unittest.TextTestRunner(verbosity=2).run(suite).wasSuccessful():
        return 1
    module = importlib.import_module("infra.scripts.test_pipeline_observability")
    functions = sorted(name for name in vars(module) if name.startswith("test_"))
    for name in functions:
        getattr(module, name)()
        print(name + " ... ok")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
