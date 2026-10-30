import os

import pytest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
SPEC_DIR = os.path.join(ROOT, "spec")
ADMIN = "https://ranger1:6182"
ADMIN2 = "https://ranger2:6182"
PDP = "https://pdp:6500"
KMS = "https://kms:9393"


@pytest.fixture
def no_sleep(monkeypatch):
    import ranger_sdk._transport.sync as s

    monkeypatch.setattr(s.time, "sleep", lambda _: None)
