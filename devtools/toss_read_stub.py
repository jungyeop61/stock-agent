#!/usr/bin/env python3
"""Deterministic, read-only Toss API stub for local process E2E checks."""

from __future__ import annotations

import argparse
import json
from datetime import UTC, date, datetime, timedelta
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

ACCESS_TOKEN = "local-read-token"
ACCOUNT_SEQ = 1001


class TossReadStubHandler(BaseHTTPRequestHandler):
    """Serve OAuth and deterministic reads while rejecting every broker mutation."""

    server_version = "JusikaTossReadStub/1.0"

    def do_POST(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
        parsed = urlparse(self.path)
        if parsed.path != "/oauth2/token":
            self._write_json(
                HTTPStatus.METHOD_NOT_ALLOWED,
                {"error": "broker mutations are not implemented by this stub"},
            )
            return

        length = int(self.headers.get("Content-Length", "0"))
        form = parse_qs(self.rfile.read(length).decode("utf-8"))
        if (
            form.get("grant_type") != ["client_credentials"]
            or not form.get("client_id")
            or not form.get("client_secret")
        ):
            self._write_json(HTTPStatus.UNAUTHORIZED, {"error": "invalid_client"})
            return

        self._write_json(
            HTTPStatus.OK,
            {
                "access_token": ACCESS_TOKEN,
                "token_type": "Bearer",
                "expires_in": 3600,
            },
        )

    def do_GET(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
        if self.headers.get("Authorization") != f"Bearer {ACCESS_TOKEN}":
            self._write_json(HTTPStatus.UNAUTHORIZED, {"error": "invalid_token"})
            return

        parsed = urlparse(self.path)
        query = parse_qs(parsed.query)
        if parsed.path == "/api/v1/accounts":
            self._write_json(
                HTTPStatus.OK,
                {
                    "result": [
                        {
                            "accountNo": "1234567890",
                            "accountSeq": ACCOUNT_SEQ,
                            "accountType": "GENERAL",
                        }
                    ]
                },
            )
            return

        if parsed.path == "/api/v1/prices":
            symbol = query.get("symbols", [""])[0].upper()
            price, currency = ("230.00", "USD") if symbol == "AAPL" else ("72000", "KRW")
            self._write_json(
                HTTPStatus.OK,
                {
                    "result": [
                        {
                            "symbol": symbol,
                            "timestamp": "2026-09-14T09:30:00+09:00",
                            "lastPrice": price,
                            "currency": currency,
                        }
                    ]
                },
            )
            return

        if parsed.path == "/api/v1/buying-power":
            if not self._has_account_header():
                return
            currency = query.get("currency", ["KRW"])[0].upper()
            amount = "1000000.00" if currency == "USD" else "100000000"
            self._write_json(
                HTTPStatus.OK,
                {"result": {"currency": currency, "cashBuyingPower": amount}},
            )
            return

        if parsed.path == "/api/v1/commissions":
            if not self._has_account_header():
                return
            self._write_json(
                HTTPStatus.OK,
                {
                    "result": [
                        {
                            "marketCountry": "KR",
                            "commissionRate": "0.00015",
                            "startDate": "2026-01-01",
                            "endDate": None,
                        },
                        {
                            "marketCountry": "US",
                            "commissionRate": "0.0009",
                            "startDate": "2026-01-01",
                            "endDate": None,
                        },
                    ]
                },
            )
            return

        if parsed.path == "/api/v1/exchange-rate":
            now = datetime.now(UTC)
            self._write_json(
                HTTPStatus.OK,
                {
                    "result": {
                        "baseCurrency": "USD",
                        "quoteCurrency": "KRW",
                        "rate": "1380.5",
                        "midRate": "1375",
                        "basisPoint": "40",
                        "rateChangeType": "UP",
                        "validFrom": (now - timedelta(minutes=5)).isoformat(),
                        "validUntil": (now + timedelta(minutes=5)).isoformat(),
                    }
                },
            )
            return

        if parsed.path == "/api/v1/market-calendar/US":
            requested_date = query.get("date", [date.today().isoformat()])[0]
            self._write_json(
                HTTPStatus.OK,
                {"result": self._us_market_calendar(requested_date)},
            )
            return

        if parsed.path == "/api/v1/sellable-quantity":
            if not self._has_account_header():
                return
            self._write_json(
                HTTPStatus.OK,
                {"result": {"sellableQuantity": "100"}},
            )
            return

        if parsed.path == "/api/v1/orders":
            if not self._has_account_header():
                return
            self._write_json(
                HTTPStatus.OK,
                {
                    "result": {
                        "orders": [self._open_order()],
                        "nextCursor": None,
                        "hasNext": False,
                    }
                },
            )
            return

        if parsed.path == "/api/v1/orders/order-123":
            if not self._has_account_header():
                return
            self._write_json(HTTPStatus.OK, {"result": self._open_order()})
            return

        if parsed.path == "/api/v1/conditional-orders":
            if not self._has_account_header():
                return
            self._write_json(
                HTTPStatus.OK,
                {
                    "result": {
                        "conditionalOrders": [self._open_conditional_order()],
                        "nextCursor": None,
                        "hasNext": False,
                    }
                },
            )
            return

        if parsed.path == "/api/v1/conditional-orders/conditional-123":
            if not self._has_account_header():
                return
            self._write_json(
                HTTPStatus.OK,
                {"result": self._open_conditional_order()},
            )
            return

        self._write_json(HTTPStatus.NOT_FOUND, {"error": "read endpoint not stubbed"})

    @staticmethod
    def _open_order() -> dict[str, object]:
        return {
            "orderId": "order-123",
            "symbol": "005930",
            "side": "BUY",
            "orderType": "LIMIT",
            "timeInForce": "DAY",
            "status": "PENDING",
            "price": "70000",
            "quantity": "10",
            "orderAmount": None,
            "currency": "KRW",
            "orderedAt": "2026-09-14T09:30:00+09:00",
            "canceledAt": None,
            "execution": {
                "filledQuantity": "0",
                "averageFilledPrice": None,
                "filledAmount": None,
                "commission": None,
                "tax": None,
                "filledAt": None,
                "settlementDate": None,
            },
        }

    @staticmethod
    def _us_market_calendar(requested_date: str) -> dict[str, object]:
        market_date = date.fromisoformat(requested_date)
        previous_date = market_date - timedelta(days=1)
        next_date = market_date + timedelta(days=1)

        def regular_session(day: date) -> dict[str, str]:
            next_day = day + timedelta(days=1)
            return {
                "startTime": f"{day.isoformat()}T22:30:00+09:00",
                "endTime": f"{next_day.isoformat()}T05:00:00+09:00",
            }

        return {
            "today": {
                "date": market_date.isoformat(),
                "dayMarket": None,
                "preMarket": None,
                "regularMarket": regular_session(market_date),
                "afterMarket": None,
            },
            "previousBusinessDay": {
                "date": previous_date.isoformat(),
                "dayMarket": None,
                "preMarket": None,
                "regularMarket": regular_session(previous_date),
                "afterMarket": None,
            },
            "nextBusinessDay": {
                "date": next_date.isoformat(),
                "dayMarket": None,
                "preMarket": None,
                "regularMarket": regular_session(next_date),
                "afterMarket": None,
            },
        }

    @staticmethod
    def _open_conditional_order() -> dict[str, object]:
        return {
            "conditionalOrderId": "conditional-123",
            "type": "SINGLE",
            "status": "WATCHING",
            "symbol": "005930",
            "market": "KR",
            "quantity": "2",
            "orderType": "MARKET",
            "expireDate": "2026-09-30",
            "first": {
                "type": "STOP",
                "status": "WATCHING",
                "triggerPrice": "80000",
                "targetProfitRate": None,
                "orderPrice": None,
                "triggeredOrderId": None,
            },
            "second": None,
            "createdAt": "2026-09-14T09:00:00+09:00",
        }

    def _has_account_header(self) -> bool:
        if self.headers.get("X-Tossinvest-Account") == str(ACCOUNT_SEQ):
            return True
        self._write_json(HTTPStatus.UNAUTHORIZED, {"error": "invalid_account"})
        return False

    def _write_json(self, status: HTTPStatus, body: object) -> None:
        payload = json.dumps(body, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, format: str, *args: object) -> None:
        # Keep local output useful while never logging credentials or headers.
        print(f"[toss-read-stub] {self.command} {urlparse(self.path).path}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", default=18081, type=int)
    args = parser.parse_args()

    server = ThreadingHTTPServer((args.host, args.port), TossReadStubHandler)
    print(f"Toss read stub listening on http://{args.host}:{args.port}")
    print("Broker mutation endpoints are disabled.")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
