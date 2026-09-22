"""QR helper checks with dummy secrets only; no broker/API access."""

import runpy
import stat
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

import pytest

ROOT = Path(__file__).resolve().parents[2]
HELPER = runpy.run_path(str(ROOT / "deploy/mobile_setup.py"))
build_payload = HELPER["build_payload"]
load_payload = HELPER["load_payload"]
TOKEN = "test_" + "x" * 38


def test_payload_matches_android_contract() -> None:
    uri = urlsplit(build_payload("jusika.example.com", TOKEN))
    assert uri.scheme == "jusika" and uri.netloc == "setup"
    assert parse_qs(uri.query) == {
        "v": ["1"],
        "endpoint": ["https://jusika.example.com"],
        "token": [TOKEN],
    }


@pytest.mark.parametrize(
    "domain",
    [
        "http://evil.com",
        "localhost",
        "127.0.0.1",
        "user@evil.com",
        "bad.example/path",
        "bad\n.example",
    ],
)
def test_unsafe_domains_rejected(domain: str) -> None:
    with pytest.raises(ValueError):
        build_payload(domain, TOKEN)


@pytest.mark.parametrize("token", ["short", "x" * 257, TOKEN + "&x=1", TOKEN + "\n"])
def test_invalid_tokens_rejected(token: str) -> None:
    with pytest.raises(ValueError):
        build_payload("jusika.example.com", token)


def test_loads_only_private_token_without_printing(tmp_path: Path, capsys) -> None:
    path = tmp_path / ".mobile-token"
    path.write_text(TOKEN + "\n")
    path.chmod(0o600)
    assert load_payload(tmp_path, "jusika.example.com") == build_payload(
        "jusika.example.com", TOKEN
    )
    assert capsys.readouterr().out == ""
    path.chmod(0o644)
    with pytest.raises(ValueError):
        load_payload(tmp_path, "jusika.example.com")


def test_rejects_symlink(tmp_path: Path) -> None:
    target = tmp_path / "other"
    target.write_text(TOKEN)
    target.chmod(0o600)
    (tmp_path / ".mobile-token").symlink_to(target)
    with pytest.raises(OSError):
        load_payload(tmp_path, "jusika.example.com")


def test_private_html_has_no_remote_assets_and_refuses_overwrite(
    tmp_path: Path,
) -> None:
    pytest.importorskip("qrcode")
    qr = HELPER["make_qr"](build_payload("jusika.example.com", TOKEN))
    path = HELPER["write_private_html"](tmp_path, qr)
    assert stat.S_IMODE(path.stat().st_mode) == 0o600
    content = path.read_text()
    assert "<svg" in content and "Content-Security-Policy" in content
    assert "<script" not in content and "<img" not in content
    assert TOKEN not in content  # QR modules, never clear-text credentials.
    with pytest.raises(FileExistsError):
        HELPER["write_private_html"](tmp_path, qr)


def test_voice_service_requires_explicit_launch_and_uses_remote_transcription() -> None:
    service = (
        ROOT / "android/app/src/main/java/com/jusika/app/VoiceStandbyService.kt"
    ).read_text()
    assert "intent?.action != ACTION_ACTIVATE" in service
    assert "client.transcribe(wav)" in service
    assert "OfflineSpeechLoop" not in service
    assert "standby()" not in service
