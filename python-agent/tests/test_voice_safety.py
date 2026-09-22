from typing import Any

import httpx
import pytest

from jusika_agent.spring_client import SpringBackendClient, SpringBackendError

SAFE: dict[str, Any] = {
    "mode": "MOCK",
    "liveEnabled": False,
    "killSwitchActive": True,
    "liveSafetyGateOpen": False,
    "liveMutationAvailable": False,
    "blockReason": "MOCK_MODE",
}


@pytest.mark.parametrize(
    "override",
    [
        {"mode": "LIVE"},
        {"mode": None},
        {"liveEnabled": True},
        {"killSwitchActive": False},
        {"liveSafetyGateOpen": True},
        {"liveMutationAvailable": True},
        {"blockReason": "NONE"},
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
            await client.check_voice_safety()
    finally:
        await client.aclose()


async def test_voice_safety_accepts_safe_mock() -> None:
    client = SpringBackendClient(
        base_url="http://spring.test",
        read_api_key="test-read",
        order_api_key="test-order",
        transport=httpx.MockTransport(lambda request: httpx.Response(200, json=SAFE)),
    )
    try:
        await client.check_voice_safety()
    finally:
        await client.aclose()


@pytest.mark.parametrize(
    "status",
    [
        {
            "mode": "LIVE",
            "liveEnabled": False,
            "killSwitchActive": True,
            "liveSafetyGateOpen": False,
            "liveMutationAvailable": False,
            "blockReason": "LIVE_FEATURE_DISABLED",
        },
        {
            "mode": "LIVE",
            "liveEnabled": True,
            "killSwitchActive": False,
            "liveSafetyGateOpen": True,
            "liveMutationAvailable": True,
            "blockReason": "NONE",
        },
    ],
)
async def test_voice_safety_accepts_guarded_or_fully_open_live(
    status: dict[str, Any],
) -> None:
    client = SpringBackendClient(
        base_url="http://spring.test",
        read_api_key="test-read",
        order_api_key="test-order",
        transport=httpx.MockTransport(lambda request: httpx.Response(200, json=status)),
    )
    try:
        await client.check_voice_safety()
    finally:
        await client.aclose()


@pytest.mark.parametrize(
    "status",
    [
        {
            "mode": "LIVE",
            "liveEnabled": True,
            "killSwitchActive": True,
            "liveSafetyGateOpen": True,
            "liveMutationAvailable": True,
            "blockReason": "NONE",
        },
        {
            "mode": "LIVE",
            "liveEnabled": True,
            "killSwitchActive": False,
            "liveSafetyGateOpen": True,
            "liveMutationAvailable": False,
            "blockReason": "NONE",
        },
    ],
)
async def test_voice_safety_rejects_inconsistent_live(status: dict[str, Any]) -> None:
    client = SpringBackendClient(
        base_url="http://spring.test",
        read_api_key="test-read",
        order_api_key="test-order",
        transport=httpx.MockTransport(lambda request: httpx.Response(200, json=status)),
    )
    try:
        with pytest.raises(SpringBackendError):
            await client.check_voice_safety()
    finally:
        await client.aclose()
