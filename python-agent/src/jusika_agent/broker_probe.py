"""Read-only production probe for the Spring-to-Toss broker connection."""

import argparse
import asyncio
from dataclasses import dataclass

from jusika_agent.config import Settings
from jusika_agent.spring_client import SpringBackendClient, SpringBackendError


@dataclass(frozen=True)
class BrokerProbeResult:
    mode: str
    live_mutation_available: bool
    block_reason: str
    account_count: int
    price_currency: str


async def probe_broker(
    client: SpringBackendClient,
    *,
    symbol: str,
    expected_mode: str,
) -> BrokerProbeResult:
    """Verify readiness and real read APIs without creating or changing an order."""
    await client.check_readiness()
    safety = await client.get_broker_safety_status()
    if safety.mode != expected_mode:
        raise SpringBackendError("설정한 증권사 실행 모드와 서버 응답이 다릅니다.")
    accounts = await client.list_accounts()
    if not accounts:
        raise SpringBackendError("토스증권에서 사용 가능한 계좌를 찾지 못했습니다.")
    price = await client.get_stock_price(symbol)
    return BrokerProbeResult(
        mode=safety.mode,
        live_mutation_available=safety.live_mutation_available,
        block_reason=safety.block_reason,
        account_count=len(accounts),
        price_currency=price.currency,
    )


async def _run(symbol: str, expected_mode: str) -> int:
    settings = Settings()
    client = SpringBackendClient(
        base_url=settings.spring_backend_url,
        read_api_key=settings.spring_read_api_key.get_secret_value(),
        order_api_key=settings.spring_order_api_key.get_secret_value(),
        connect_timeout_seconds=settings.spring_connect_timeout_seconds,
        read_timeout_seconds=settings.spring_read_timeout_seconds,
        read_max_attempts=settings.spring_read_max_attempts,
        retry_base_delay_seconds=settings.spring_retry_base_delay_seconds,
    )
    try:
        result = await probe_broker(
            client,
            symbol=symbol,
            expected_mode=expected_mode,
        )
    except (SpringBackendError, ValueError):
        print("FAIL: 실제 증권사 읽기 연결 검증에 실패했습니다. 컨테이너 로그를 확인하세요.")
        return 1
    finally:
        await client.aclose()
    print(f"PASS: Spring 준비 완료, 증권사 모드 {result.mode}")
    print(f"PASS: 실제 계좌 조회 {result.account_count}개")
    print(f"PASS: 실제 현재가 조회 통화 {result.price_currency}")
    print(
        "INFO: 실제 주문 변경 "
        + ("가능" if result.live_mutation_available else f"차단됨 ({result.block_reason})")
    )
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(
        description="실제 주문을 만들지 않고 Spring-to-Toss 연결을 검증합니다."
    )
    parser.add_argument("--symbol", default="005930")
    parser.add_argument("--expect-mode", choices=["MOCK", "LIVE"], default="LIVE")
    args = parser.parse_args()
    return asyncio.run(_run(args.symbol, args.expect_mode))


if __name__ == "__main__":
    raise SystemExit(main())
