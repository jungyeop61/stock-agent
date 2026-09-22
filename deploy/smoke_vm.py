"""Docker Desktop smoke test: disposable data, local TLS, read stub, MOCK orders only."""

import json
import runpy
import subprocess
import tempfile
import threading
from http.server import ThreadingHTTPServer
from pathlib import Path
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[1]
create_environment = runpy.run_path(str(ROOT / "deploy/init_environment.py"))["create_environment"]
StubHandler = runpy.run_path(str(ROOT / "devtools/toss_read_stub.py"))["TossReadStubHandler"]

CLIENT = r"""
import http.client, json, socket, ssl, sys, time
data = json.load(sys.stdin)
context = ssl.create_default_context(cadata=data["ca"])
def request(path, body=None, auth=True):
    connection = http.client.HTTPSConnection("localhost", context=context, timeout=30)
    for attempt in range(20):
        try:
            raw = socket.create_connection(("172.30.91.2", 443), timeout=3)
            connection.sock = context.wrap_socket(raw, server_hostname="localhost")
            break
        except OSError:
            if attempt == 19: raise
            time.sleep(1)
    headers = {"Content-Type": "application/json"}
    if auth: headers["Authorization"] = "Bearer " + data["token"]
    # An attacker cannot override the proxy's scheme or peer.
    headers["X-Forwarded-Proto"] = "http"
    headers["X-Forwarded-For"] = "127.0.0.1"
    connection.request("POST" if body is not None else "GET", path,
                       json.dumps(body) if body is not None else None, headers)
    response = connection.getresponse()
    status, payload = response.status, response.read()
    connection.close()
    return status, payload
path = "/api/agent/sessions/" + data["session"] + "/voice-messages"
if data["stage"] == "prepare":
    assert request("/health", auth=False)[0] == 200
    for private in ["/ready", "/docs", path.replace("voice-messages", "messages")]:
        assert request(private, auth=False)[0] == 404
    assert request(path, {"text": "삼성전자 현재가 알려줘"}, auth=False)[0] == 401
    status, raw = request(path, {"text": "삼성전자 현재가 알려줘"})
    assert status == 200 and json.loads(raw)["status"] == "COMPLETED"
    status, raw = request(path, {"text": "삼성전자 1주 사줘"})
    preview = json.loads(raw)
    assert status == 200 and preview["status"] == "WAITING_CONFIRMATION"
    print(json.dumps({"preview_id": preview["preview_id"]}))
else:
    approval = {"text": "승인", "confirmation_preview_id": data["preview_id"]}
    status, raw = request(path, approval)
    assert status == 200 and json.loads(raw)["status"] == "COMPLETED"
    status, raw = request(path, approval)
    assert status == 200 and json.loads(raw)["status"] == "ERROR"
    print("{}")
"""


def run(command: list[str], *, input_text: str | None = None) -> str:
    result = subprocess.run(
        command,
        input=input_text,
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode:
        # Compose errors/configuration can contain secrets. Never print captured output.
        raise RuntimeError(
            "Smoke command failed; inspect local containers without sharing secrets."
        )
    return result.stdout


def main() -> None:
    project = "jusika-smoke-" + uuid4().hex[:12]
    with tempfile.TemporaryDirectory(prefix="jusika-vm-smoke-") as temporary:
        directory = Path(temporary)
        env, token = create_environment(directory, "jusika.example.com", "owner@example.com")
        config = json.loads(
            run(
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
                ]
            )
        )
        config["name"] = project
        # Compose's normalized resource names otherwise retain the production project name.
        for resources in [config["networks"], config["volumes"]]:
            for key, value in resources.items():
                value["name"] = project + "_" + key
        config["services"]["caddy"]["ports"] = []
        config["services"]["caddy"]["environment"]["JUSIKA_DOMAIN"] = "localhost"
        server = ThreadingHTTPServer(("0.0.0.0", 0), StubHandler)
        spring = config["services"]["spring"]["environment"]
        spring.update(
            {
                "TOSSINVEST_BASE_URL": f"http://host.docker.internal:{server.server_port}",
                "TOSSINVEST_CLIENT_ID": "local-stub",
                "TOSSINVEST_CLIENT_SECRET": "local-stub",
            }
        )
        # Local smoke tests stay deterministic and never call the paid OpenAI interpreter.
        config["services"]["agent"]["environment"]["JUSIKA_AGENT_COMMAND_INTERPRETER"] = (
            "rules"
        )
        fixture = directory / "compose.json"
        fixture.touch(mode=0o600)
        fixture.write_text(json.dumps(config))
        compose = ["docker", "compose", "-p", project, "-f", str(fixture)]
        threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            print("Starting disposable VM stack (no public host ports)...", flush=True)
            run(compose + ["up", "-d", "--wait", "--wait-timeout", "180"])
            ca = run(
                compose
                + ["exec", "-T", "caddy", "cat", "/data/caddy/pki/authorities/local/root.crt"]
            )
            data = {
                "ca": ca,
                "token": token.read_text().strip(),
                "session": str(uuid4()),
                "stage": "prepare",
            }
            preview = json.loads(
                run(
                    compose + ["exec", "-T", "agent", "python", "-c", CLIENT],
                    input_text=json.dumps(data),
                )
            )
            print("TLS/auth/read/MOCK preview passed; restarting agent...", flush=True)
            run(compose + ["restart", "agent"])
            run(compose + ["up", "-d", "--wait", "--wait-timeout", "90"])
            data.update(preview)
            data["stage"] = "approve"
            run(
                compose + ["exec", "-T", "agent", "python", "-c", CLIENT],
                input_text=json.dumps(data),
            )
            print("PASS: persistent approval after restart and duplicate approval rejection.")
        finally:
            # Only the newly generated test project/data are removed, never production data.
            run(compose + ["down", "--volumes"])
            server.shutdown()
            server.server_close()


if __name__ == "__main__":
    main()
