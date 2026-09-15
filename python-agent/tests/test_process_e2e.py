"""Opt-in wrapper for the complete local real-process E2E suite."""

import os
import subprocess
import sys
from pathlib import Path

import pytest

_RUN_PROCESS_E2E = os.getenv("RUN_JUSIKA_AGENT_PROCESS_E2E", "false").lower() == "true"
pytestmark = [
    pytest.mark.process_e2e,
    pytest.mark.skipif(
        not _RUN_PROCESS_E2E,
        reason="RUN_JUSIKA_AGENT_PROCESS_E2E=true is required",
    ),
]


def test_complete_agent_spring_process_flow() -> None:
    root = Path(__file__).resolve().parents[2]
    completed = subprocess.run(
        [sys.executable, str(root / "devtools" / "run_agent_process_e2e.py")],
        cwd=root,
        capture_output=True,
        text=True,
        timeout=240,
        check=False,
    )

    assert completed.returncode == 0, f"{completed.stdout}\n{completed.stderr}"
    assert "PASS: complete real-process" in completed.stdout
