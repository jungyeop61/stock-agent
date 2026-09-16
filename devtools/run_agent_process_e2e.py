#!/usr/bin/env python3
"""Run the complete Agent-to-Spring MOCK flow in real local processes."""

from __future__ import annotations

import argparse
import json
import os
import signal
import socket
import subprocess
import sys
import tempfile
import time
from collections.abc import Sequence
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any, TextIO
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[1]
SPRING_DIR = ROOT / "spring-backend"
AGENT_DIR = ROOT / "python-agent"
STUB_PATH = ROOT / "devtools" / "toss_read_stub.py"
READ_KEY = "local-e2e-read-key"
ORDER_KEY = "local-e2e-order-key"


class ProcessE2EError(RuntimeError):
    """Raised when a process or an HTTP assertion fails."""


def available_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
        listener.bind(("127.0.0.1", 0))
        return int(listener.getsockname()[1])


def wait_for_http(
    url: str,
    *,
    process: subprocess.Popen[str],
    timeout_seconds: float,
) -> None:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        return_code = process.poll()
        if return_code is not None:
            raise ProcessE2EError(f"process exited before startup: code={return_code}")
        try:
            with urlopen(url, timeout=1) as response:
                if response.status < 500:
                    return
        except HTTPError as exc:
            if exc.code < 500:
                return
            time.sleep(0.2)
        except (URLError, TimeoutError):
            time.sleep(0.2)
    raise ProcessE2EError(f"startup timed out: {url}")


def post_message(agent_url: str, session_id: str, text: str) -> dict[str, Any]:
    body = json.dumps({"text": text}, ensure_ascii=False).encode("utf-8")
    request = Request(
        f"{agent_url}/api/agent/sessions/{session_id}/messages",
        data=body,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urlopen(request, timeout=20) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except HTTPError as exc:
        raise ProcessE2EError(f"agent returned HTTP {exc.code}") from exc
    except (URLError, TimeoutError, ValueError) as exc:
        raise ProcessE2EError("agent response could not be read") from exc
    if not isinstance(payload, dict):
        raise ProcessE2EError("agent response was not a JSON object")
    return payload


def require_status(payload: dict[str, Any], expected: str, label: str) -> None:
    actual = payload.get("status")
    if actual != expected:
        message = str(payload.get("message") or "")
        raise ProcessE2EError(f"{label}: expected {expected}, got {actual}: {message}")


def run_read_case(agent_url: str, label: str, text: str) -> None:
    payload = post_message(agent_url, str(uuid4()), text)
    require_status(payload, "COMPLETED", label)
    print(f"PASS read: {label}")


def run_rejection_case(agent_url: str, label: str, text: str) -> None:
    payload = post_message(agent_url, str(uuid4()), text)
    require_status(payload, "NEEDS_INPUT", label)
    if payload.get("requires_confirmation") is True:
        raise ProcessE2EError(f"{label}: rejected command requested confirmation")
    print(f"PASS rejection: {label}")


def run_mutation_case(agent_url: str, label: str, text: str) -> dict[str, Any]:
    session_id = str(uuid4())
    preview = post_message(agent_url, session_id, text)
    require_status(preview, "WAITING_CONFIRMATION", f"{label} preview")
    preview_id = preview.get("preview_id")
    if not isinstance(preview_id, str) or not preview_id:
        raise ProcessE2EError(f"{label}: preview ID is missing")
    if preview.get("requires_confirmation") is not True:
        raise ProcessE2EError(f"{label}: explicit confirmation was not required")

    execution = post_message(agent_url, session_id, "승인")
    require_status(execution, "COMPLETED", f"{label} execution")
    data = execution.get("data")
    if not isinstance(data, dict) or data.get("brokerMode") != "MOCK":
        raise ProcessE2EError(f"{label}: execution did not remain in MOCK mode")
    if data.get("status") != "ACCEPTED":
        raise ProcessE2EError(f"{label}: execution was not accepted")
    print(f"PASS mutation: {label}")
    return data


def run_recovery_conflict_case(agent_url: str, execution_id: str) -> None:
    session_id = str(uuid4())
    preview = post_message(
        agent_url,
        session_id,
        f"실행번호 {execution_id} 복구해줘",
    )
    require_status(preview, "WAITING_CONFIRMATION", "execution recovery confirmation")
    response = post_message(agent_url, session_id, "승인")
    require_status(response, "ERROR", "accepted execution recovery rejection")
    print("PASS safety: accepted execution cannot be recovered again")


def run_multiturn_case(agent_url: str) -> None:
    session_id = str(uuid4())
    stock_prompt = post_message(agent_url, session_id, "사줘")
    require_status(stock_prompt, "NEEDS_INPUT", "multi-turn stock prompt")
    quantity_prompt = post_message(agent_url, session_id, "삼성전자")
    require_status(quantity_prompt, "NEEDS_INPUT", "multi-turn quantity prompt")
    preview = post_message(agent_url, session_id, "5주")
    require_status(preview, "WAITING_CONFIRMATION", "multi-turn preview")
    execution = post_message(agent_url, session_id, "승인")
    require_status(execution, "COMPLETED", "multi-turn execution")
    print("PASS conversation: multi-turn slot collection")


def process_environment(
    stub_port: int,
    spring_port: int,
    agent_port: int,
) -> tuple[dict[str, str], dict[str, str]]:
    base = os.environ.copy()
    spring_environment = base | {
        "TOSSINVEST_BASE_URL": f"http://127.0.0.1:{stub_port}",
        "TOSSINVEST_CLIENT_ID": "local-client",
        "TOSSINVEST_CLIENT_SECRET": "local-secret",
        "JUSIKA_INTERNAL_READ_API_KEY": READ_KEY,
        "JUSIKA_INTERNAL_ORDER_API_KEY": ORDER_KEY,
        "JUSIKA_BROKER_MODE": "mock",
        "JUSIKA_LIVE_TRADING_ENABLED": "false",
        "JUSIKA_TRADING_KILL_SWITCH_ACTIVE": "true",
        "JUSIKA_LIVE_ALLOWED_ACCOUNT_SEQS": "",
        "JUSIKA_LIVE_ALLOWED_INSTRUMENTS": "",
        "JUSIKA_LIVE_MAX_ORDER_QUANTITY": "0",
        "JUSIKA_LIVE_MAX_KRW_ORDER_AMOUNT": "0",
        "JUSIKA_LIVE_MAX_USD_ORDER_AMOUNT": "0",
        "JUSIKA_LIVE_QUANTITY_ORDER_SUBMISSION_CONNECTED": "false",
        "JUSIKA_LIVE_AMOUNT_ORDER_SUBMISSION_CONNECTED": "false",
        "JUSIKA_LIVE_NORMAL_ORDER_CANCELLATION_CONNECTED": "false",
        "JUSIKA_LIVE_NORMAL_ORDER_MODIFICATION_CONNECTED": "false",
        "JUSIKA_LIVE_SINGLE_CONDITIONAL_ORDER_CREATION_CONNECTED": "false",
        "JUSIKA_LIVE_OCO_CONDITIONAL_ORDER_CREATION_CONNECTED": "false",
        "JUSIKA_LIVE_OTO_CONDITIONAL_ORDER_CREATION_CONNECTED": "false",
        "JUSIKA_LIVE_CONDITIONAL_ORDER_CANCELLATION_CONNECTED": "false",
        "JUSIKA_LIVE_CONDITIONAL_ORDER_MODIFICATION_CONNECTED": "false",
        "SPRING_SERVER_PORT": str(spring_port),
    }
    agent_environment = base | {
        "JUSIKA_AGENT_ENVIRONMENT": "process-e2e",
        "JUSIKA_AGENT_SPRING_BACKEND_URL": f"http://127.0.0.1:{spring_port}",
        "JUSIKA_INTERNAL_READ_API_KEY": READ_KEY,
        "JUSIKA_INTERNAL_ORDER_API_KEY": ORDER_KEY,
        "JUSIKA_AGENT_COMMAND_INTERPRETER": "rules",
        "JUSIKA_AGENT_CHECKPOINT_PROVIDER": "memory",
        "JUSIKA_AGENT_PORT": str(agent_port),
        "PYTHONPATH": str(AGENT_DIR / "src"),
    }
    return spring_environment, agent_environment


def start_process(
    command: Sequence[str],
    *,
    cwd: Path,
    environment: dict[str, str],
    log_path: Path,
) -> tuple[subprocess.Popen[str], TextIO]:
    log_file = log_path.open("w", encoding="utf-8")
    process = subprocess.Popen(
        command,
        cwd=cwd,
        env=environment,
        stdout=log_file,
        stderr=subprocess.STDOUT,
        text=True,
        start_new_session=True,
    )
    return process, log_file


def stop_process(process: subprocess.Popen[str]) -> None:
    if process.poll() is not None:
        return
    os.killpg(process.pid, signal.SIGTERM)
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGKILL)
        process.wait(timeout=5)


def sanitized_log_tail(path: Path, line_count: int = 60) -> str:
    if not path.exists():
        return ""
    lines = path.read_text(encoding="utf-8", errors="replace").splitlines()[-line_count:]
    text = "\n".join(lines)
    for secret in (READ_KEY, ORDER_KEY, "local-secret"):
        text = text.replace(secret, "[REDACTED]")
    return text


def assert_agent_log_safety(path: Path) -> None:
    content = path.read_text(encoding="utf-8", errors="replace")
    for event in (
        '"event":"http.request.completed"',
        '"event":"agent.turn.completed"',
        '"event":"spring.request.completed"',
    ):
        if event not in content:
            raise ProcessE2EError(f"structured agent log event is missing: {event}")
    for forbidden in (
        READ_KEY,
        ORDER_KEY,
        "local-secret",
        "삼성전자 5주 사줘",
        "1234567890",
        "order-123",
        "conditional-123",
    ):
        if forbidden in content:
            raise ProcessE2EError("agent log exposed a protected value")
    print("PASS observability: structured logs contain no protected test values")


def run_suite(agent_url: str) -> None:
    expiry = (datetime.now(UTC).date() + timedelta(days=30)).isoformat()
    read_cases = [
        ("stock price", "삼성전자 지금 얼마야"),
        ("exchange rate", "지금 달러 환율 알려줘"),
        ("currency conversion", "100달러는 원화로 얼마야"),
        ("holdings", "내 보유 주식 알려줘"),
        ("buying power", "원화 주문 가능 금액 알려줘"),
        ("commissions", "내 주식 수수료 알려줘"),
        ("sellable quantity", "삼성전자 매도 가능 수량 알려줘"),
        ("open orders", "미체결 주문 알려줘"),
        ("complete order history", "전체 주문 내역 알려줘"),
        ("order detail", "주문번호 order-123 상태 알려줘"),
        ("conditional orders", "조건 주문 목록 알려줘"),
        (
            "conditional order detail",
            "조건주문번호 conditional-123 상세 알려줘",
        ),
    ]
    mutation_cases = [
        ("quantity buy", "삼성전자 5주 사줘"),
        ("quantity sell", "삼성전자 2주 팔아"),
        ("USD amount buy", "애플 200달러어치 사줘"),
        ("order cancellation", "주문번호 order-123 취소해줘"),
        ("order modification", "주문번호 order-123을 7주 71,000원으로 정정해줘"),
        (
            "single conditional order",
            f"삼성전자 80,000원이 되면 시장가로 2주 매도 조건주문 만료일 {expiry}",
        ),
        (
            "OCO conditional order",
            " ".join(
                [
                    "삼성전자 2주 OCO 조건주문",
                    "첫 조건 감시가 80,000원 주문가 79,000원",
                    f"둘째 조건 감시가 65,000원 주문가 64,900원 만료일 {expiry}",
                ]
            ),
        ),
        (
            "OTO conditional order",
            " ".join(
                [
                    "삼성전자 2주 OTO 조건주문",
                    "첫 조건 감시가 68,000원 주문가 69,000원",
                    f"둘째 조건 감시가 79,000원 주문가 80,000원 만료일 {expiry}",
                ]
            ),
        ),
        ("conditional cancellation", "조건주문번호 conditional-123 취소해줘"),
        (
            "conditional modification",
            " ".join(
                [
                    "조건주문번호 conditional-123을 OCO 조건주문으로 정정 2주",
                    "첫 조건 감시가 80,000원 주문가 79,000원",
                    f"둘째 조건 감시가 65,000원 주문가 64,900원 만료일 {expiry}",
                ]
            ),
        ),
    ]

    for label, command in read_cases:
        run_read_case(agent_url, label, command)
    run_rejection_case(agent_url, "unsupported real currency exchange", "10만 원을 달러로 환전해줘")
    run_multiturn_case(agent_url)
    quantity_execution: dict[str, Any] | None = None
    for label, command in mutation_cases:
        execution = run_mutation_case(agent_url, label, command)
        if label == "quantity buy":
            quantity_execution = execution
    if quantity_execution is None or not isinstance(quantity_execution.get("executionId"), str):
        raise ProcessE2EError("quantity execution ID is missing")
    execution_id = str(quantity_execution["executionId"])
    run_read_case(
        agent_url,
        "execution status",
        f"실행번호 {execution_id} 상태 알려줘",
    )
    run_recovery_conflict_case(agent_url, execution_id)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--startup-timeout", type=float, default=120)
    args = parser.parse_args()

    stub_port, spring_port, agent_port = (available_port() for _ in range(3))
    spring_environment, agent_environment = process_environment(
        stub_port,
        spring_port,
        agent_port,
    )
    processes: list[subprocess.Popen[str]] = []
    log_files: list[TextIO] = []

    with tempfile.TemporaryDirectory(prefix="jusika-process-e2e-") as temporary_directory:
        log_directory = Path(temporary_directory)
        log_paths = {
            "stub": log_directory / "stub.log",
            "spring": log_directory / "spring.log",
            "agent": log_directory / "agent.log",
        }
        try:
            stub, stub_log = start_process(
                [sys.executable, str(STUB_PATH), "--port", str(stub_port)],
                cwd=ROOT,
                environment=os.environ.copy(),
                log_path=log_paths["stub"],
            )
            processes.append(stub)
            log_files.append(stub_log)
            wait_for_http(
                f"http://127.0.0.1:{stub_port}/not-found",
                process=stub,
                timeout_seconds=10,
            )

            spring, spring_log = start_process(
                [str(SPRING_DIR / "mvnw"), "spring-boot:run"],
                cwd=SPRING_DIR,
                environment=spring_environment,
                log_path=log_paths["spring"],
            )
            processes.append(spring)
            log_files.append(spring_log)
            wait_for_http(
                f"http://127.0.0.1:{spring_port}/actuator/health",
                process=spring,
                timeout_seconds=args.startup_timeout,
            )

            agent, agent_log = start_process(
                [sys.executable, "-m", "jusika_agent"],
                cwd=AGENT_DIR,
                environment=agent_environment,
                log_path=log_paths["agent"],
            )
            processes.append(agent)
            log_files.append(agent_log)
            agent_url = f"http://127.0.0.1:{agent_port}"
            wait_for_http(
                f"{agent_url}/health",
                process=agent,
                timeout_seconds=30,
            )
            wait_for_http(
                f"{agent_url}/ready",
                process=agent,
                timeout_seconds=30,
            )

            run_suite(agent_url)
            agent_log.flush()
            assert_agent_log_safety(log_paths["agent"])
            print("PASS: complete real-process Agent -> Spring -> Toss read stub E2E")
            return 0
        except (OSError, ProcessE2EError, subprocess.SubprocessError) as exc:
            print(f"FAIL: {exc}", file=sys.stderr)
            for name, path in log_paths.items():
                tail = sanitized_log_tail(path)
                if tail:
                    print(f"\n--- {name} log tail ---\n{tail}", file=sys.stderr)
            return 1
        finally:
            for process in reversed(processes):
                stop_process(process)
            for log_file in log_files:
                log_file.close()


if __name__ == "__main__":
    raise SystemExit(main())
