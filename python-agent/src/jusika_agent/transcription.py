"""Bounded OpenAI speech transcription boundary for explicitly captured mobile turns."""

from typing import Protocol

from openai import AsyncOpenAI


class SpeechTranscriber(Protocol):
    async def transcribe(self, audio: bytes) -> str: ...

    async def aclose(self) -> None: ...


class OpenAISpeechTranscriber:
    """Transcribe one completed Korean WAV utterance without retaining it locally."""

    def __init__(self, *, api_key: str, model: str, timeout_seconds: float) -> None:
        if not api_key:
            raise ValueError("OpenAI 음성 인식에는 API 키가 필요합니다.")
        if not model:
            raise ValueError("OpenAI 음성 인식 모델이 필요합니다.")
        self._model = model
        self._client = AsyncOpenAI(
            api_key=api_key,
            timeout=timeout_seconds,
            max_retries=2,
        )

    async def transcribe(self, audio: bytes) -> str:
        result = await self._client.audio.transcriptions.create(
            model=self._model,
            file=("voice.wav", audio, "audio/wav"),
            prompt=(
                "한국어 주식 음성 명령입니다. 종목명, 숫자, 원화, 달러, 주문번호, "
                "조건주문번호, 실행번호, 승인, 취소를 정확히 받아쓰세요."
            ),
            extra_body={
                "keywords": [
                    "삼성전자",
                    "애플",
                    "주문번호",
                    "조건주문번호",
                    "실행번호",
                    "승인",
                    "취소",
                ],
                "languages": ["ko"],
            },
        )
        text = result.text.strip()
        if not text or len(text) > 500:
            raise ValueError("음성 인식 결과가 비어 있거나 너무 깁니다.")
        return text

    async def aclose(self) -> None:
        await self._client.close()
