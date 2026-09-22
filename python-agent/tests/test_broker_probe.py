import httpx

from jusika_agent.broker_probe import probe_broker
from jusika_agent.spring_client import SpringBackendClient


async def test_probe_uses_only_read_only_endpoints() -> None:
    requests: list[tuple[str, str]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append((request.method, request.url.path))
        if request.url.path == "/actuator/health":
            return httpx.Response(200, json={"status": "UP"})
        if request.url.path == "/api/broker/safety":
            return httpx.Response(
                200,
                json={
                    "mode": "LIVE",
                    "liveEnabled": False,
                    "killSwitchActive": True,
                    "liveSafetyGateOpen": False,
                    "liveMutationAvailable": False,
                    "blockReason": "LIVE_FEATURE_DISABLED",
                },
            )
        if request.url.path == "/api/accounts":
            return httpx.Response(
                200,
                json=[
                    {
                        "accountSeq": 7,
                        "maskedAccountNumber": "****1234",
                        "accountType": "GENERAL",
                    }
                ],
            )
        if request.url.path == "/api/stocks/005930/price":
            return httpx.Response(
                200,
                json={
                    "symbol": "005930",
                    "price": "70000",
                    "currency": "KRW",
                    "timestamp": "2026-09-22T09:00:00+09:00",
                },
            )
        raise AssertionError(f"unexpected path: {request.url.path}")

    client = SpringBackendClient(
        base_url="http://spring.test",
        read_api_key="test-read",
        order_api_key="test-order",
        transport=httpx.MockTransport(handler),
    )
    try:
        result = await probe_broker(client, symbol="005930", expected_mode="LIVE")
    finally:
        await client.aclose()

    assert result.account_count == 1
    assert result.live_mutation_available is False
    assert all(method == "GET" for method, _ in requests)
    assert requests == [
        ("GET", "/actuator/health"),
        ("GET", "/api/broker/safety"),
        ("GET", "/api/accounts"),
        ("GET", "/api/stocks/005930/price"),
    ]
