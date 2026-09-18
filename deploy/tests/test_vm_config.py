"""Offline deployment-contract checks; no containers or cloud resources used."""

import json
import runpy
import shutil
import stat
import subprocess
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
HELPER = runpy.run_path(str(ROOT / "deploy/init_environment.py"))
create_environment = HELPER["create_environment"]
validate_domain = HELPER["validate_domain"]


@pytest.mark.parametrize(
    "domain",
    [
        "https://example.com",
        "localhost",
        "127.0.0.1",
        "bad\nexample.com",
        "-bad.example.com",
        "*.example.com",
    ],
)
def test_domain_rejects_urls_ips_and_injection(domain: str) -> None:
    with pytest.raises(ValueError):
        validate_domain(domain)


def test_private_files_distinct_secrets_and_no_output(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    env, token = create_environment(tmp_path, "jusika.example.com", "owner@example.com")
    assert stat.S_IMODE(env.stat().st_mode) == 0o600
    assert stat.S_IMODE(token.stat().st_mode) == 0o600
    values = dict(line.split("=", 1) for line in env.read_text().splitlines() if "=" in line)
    credentials = json.loads(values["JUSIKA_AGENT_MOBILE_CREDENTIALS"].strip("'"))
    assert credentials["father"] == token.read_text().strip()
    secrets = [
        values["POSTGRES_PASSWORD"],
        values["JUSIKA_INTERNAL_READ_API_KEY"],
        values["JUSIKA_INTERNAL_ORDER_API_KEY"],
        credentials["father"],
    ]
    assert len(set(secrets)) == 4
    assert all(len(value) == 43 for value in secrets)
    assert values["TOSSINVEST_CLIENT_ID"] == values["TOSSINVEST_CLIENT_SECRET"] == ""
    assert capsys.readouterr().out == ""


def test_existing_credentials_never_overwritten(tmp_path: Path) -> None:
    env, token = create_environment(tmp_path, "jusika.example.com", "owner@example.com")
    previous = env.read_bytes(), token.read_bytes()
    with pytest.raises(FileExistsError):
        create_environment(tmp_path, "other.example.com", "owner@example.com")
    assert previous == (env.read_bytes(), token.read_bytes())


def test_invalid_email_creates_no_secret_files(tmp_path: Path) -> None:
    with pytest.raises(ValueError):
        create_environment(tmp_path, "jusika.example.com", "owner@example.com\nEVIL=value")
    assert list(tmp_path.iterdir()) == []


def test_compose_has_only_https_entrypoint_and_hardcoded_safety(tmp_path: Path) -> None:
    if not shutil.which("docker"):
        pytest.skip("Docker CLI required for Compose syntax verification")
    env, _ = create_environment(tmp_path, "jusika.example.com", "owner@example.com")
    result = subprocess.run(
        [
            "docker",
            "compose",
            "--env-file",
            str(env),
            "-f",
            str(ROOT / "deploy/compose.yaml"),
            "config",
            "--format",
            "json",
        ],
        check=True,
        capture_output=True,
        text=True,
    )
    services = json.loads(result.stdout)["services"]
    assert set(services) == {"postgres", "spring", "agent", "caddy"}
    assert all(not services[name].get("ports") for name in ["postgres", "spring", "agent"])
    assert {str(port["published"]) for port in services["caddy"]["ports"]} == {"80", "443"}
    spring = services["spring"]["environment"]
    assert spring["JUSIKA_BROKER_MODE"] == "mock"
    assert spring["JUSIKA_LIVE_TRADING_ENABLED"] == "false"
    assert spring["JUSIKA_TRADING_KILL_SWITCH_ACTIVE"] == "true"
    agent = services["agent"]["environment"]
    assert agent["JUSIKA_AGENT_MOBILE_AUTH_REQUIRED"] == "true"
    assert agent["JUSIKA_AGENT_CHECKPOINT_PROVIDER"] == "postgres"
    assert agent["JUSIKA_AGENT_COMMAND_INTERPRETER"] == "rules"
    assert "OPENAI_API_KEY" not in agent
    assert json.loads(agent["JUSIKA_AGENT_MOBILE_CREDENTIALS"])["father"]
    assert sum(int(service["mem_limit"]) for service in services.values()) < 1800 * 1024 * 1024
