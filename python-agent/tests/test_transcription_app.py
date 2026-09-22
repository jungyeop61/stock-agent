from fastapi.testclient import TestClient
from langgraph.checkpoint.memory import InMemorySaver

from jusika_agent.app import create_app
from jusika_agent.config import Settings
from jusika_agent.interpreters import RuleBasedCommandInterpreter
from tests.fakes import FakeSpringGateway


class FakeTranscriber:
    def __init__(self, text: str = "삼성전자 현재가 알려줘") -> None:
        self.text = text
        self.audio: bytes | None = None
        self.closed = False

    async def transcribe(self, audio: bytes) -> str:
        self.audio = audio
        return self.text

    async def aclose(self) -> None:
        self.closed = True


def wav(size: int = 44) -> bytes:
    return b"RIFF" + b"\x00" * 4 + b"WAVE" + b"\x00" * (size - 12)


def test_authenticated_wav_is_transcribed_without_exposing_provider_key() -> None:
    transcriber = FakeTranscriber()
    app = create_app(
        settings=Settings(_env_file=None),
        interpreter=RuleBasedCommandInterpreter(),
        spring=FakeSpringGateway(),
        checkpointer=InMemorySaver(),
        transcriber=transcriber,
    )
    with TestClient(app, client=("127.0.0.1", 50000)) as client:
        response = client.post(
            "/api/agent/transcriptions",
            content=wav(32044),
            headers={"Content-Type": "audio/wav"},
        )
    assert response.status_code == 200
    assert response.json() == {"text": "삼성전자 현재가 알려줘"}
    assert transcriber.audio == wav(32044)


def test_transcription_rejects_wrong_type_shape_and_oversize() -> None:
    app = create_app(
        settings=Settings(_env_file=None),
        interpreter=RuleBasedCommandInterpreter(),
        spring=FakeSpringGateway(),
        checkpointer=InMemorySaver(),
        transcriber=FakeTranscriber(),
    )
    with TestClient(app, client=("127.0.0.1", 50000)) as client:
        assert client.post("/api/agent/transcriptions", content=wav()).status_code == 415
        assert client.post(
            "/api/agent/transcriptions", content=b"not-wave", headers={"Content-Type": "audio/wav"}
        ).status_code == 400
        assert client.post(
            "/api/agent/transcriptions",
            content=wav(1_000_045),
            headers={"Content-Type": "audio/wav"},
        ).status_code == 413


def test_transcription_fails_closed_when_openai_is_not_configured() -> None:
    app = create_app(
        settings=Settings(_env_file=None),
        interpreter=RuleBasedCommandInterpreter(),
        spring=FakeSpringGateway(),
        checkpointer=InMemorySaver(),
    )
    with TestClient(app, client=("127.0.0.1", 50000)) as client:
        response = client.post(
            "/api/agent/transcriptions",
            content=wav(),
            headers={"Content-Type": "audio/wav"},
        )
    assert response.status_code == 503
