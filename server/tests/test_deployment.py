import importlib.util
from pathlib import Path

import pytest

spec = importlib.util.spec_from_file_location("verify_deployment", Path(__file__).resolve().parents[2] / "ops/verify_deployment.py")
deployment = importlib.util.module_from_spec(spec)
spec.loader.exec_module(deployment)


def test_deployment_requires_actual_release_identity_and_recall_route():
    health = {"status": "ok", "version": "2.0.3", "build": "abc"}
    schema = {"paths": {deployment.RECALL_PATH: {"post": {}}}}
    deployment.validate(health, schema, "2.0.3", "abc")
    for key in ("status", "version", "build"):
        with pytest.raises(ValueError, match="identity"):
            deployment.validate(health | {key: "old"}, schema, "2.0.3", "abc")
    for missing in ({}, {"paths": {deployment.RECALL_PATH: {"get": {}}}}):
        with pytest.raises(ValueError, match="recall"):
            deployment.validate(health, missing, "2.0.3", "abc")
