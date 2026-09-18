from typing import Any

import httpx
import pytest

from jusika_agent.spring_client import SpringBackendClient, SpringBackendError

SAFE: dict[str, Any] = {
    "mode": "MOCK",
    "liveEnabled": False,
    "killSwitchActive": True,
    "liveMutationAvailable": False,
}


@pytest.mark.parametrize(
    "override",
    [
        {"mode": "LIVE"},
        {"mode": None},
        {"liveEnabled": True},
        {"killSwitchActive": False},
        {"liveMutationAvailable": True},
        {"killSwitchActive": "true"},
        {"liveEnabled": 0},
    ],
)
async def test_voice_safety_rejects_unsafe_or_malformed_backend(override: dict[str, Any]) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.method == "GET"
        assert request.url.path == "/api/broker/safety"
        return httpx.Response(200, json=SAFE | override)

    client = SpringBackendClient(
        base_url="http://spring.test",
        read_api_key="test-read",
        order_api_key="test-order",
        transport=httpx.MockTransport(handler),
    )
    try:
        with pytest.raises(SpringBackendError):
            await client.check_mock_safety()
    finally:
        await client.aclose()


async def test_voice_safety_accepts_only_mock_with_live_disabled_and_kill_switch_active() -> None:
    client = SpringBackendClient(
        base_url="http://spring.test",
        read_api_key="test-read",
        order_api_key="test-order",
        transport=httpx.MockTransport(lambda request: httpx.Response(200, json=SAFE)),
    )
    try:
        await client.check_mock_safety()
    finally:
        await client.aclose()
