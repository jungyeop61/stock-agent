"""Deterministic stock-name resolution used before calling Spring."""

import re
from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class Instrument:
    """A stock identifier that may safely be sent to Spring for validation."""

    symbol: str
    display_name: str


class InstrumentResolutionError(ValueError):
    """Raised when a command does not identify one supported instrument."""


class InstrumentCatalog:
    """Small MVP alias catalog; Spring remains the source of truth for existence and price."""

    _aliases: dict[str, Instrument] = {
        "삼성전자": Instrument("005930", "삼성전자"),
        "삼전": Instrument("005930", "삼성전자"),
        "애플": Instrument("AAPL", "애플"),
        "aapl": Instrument("AAPL", "애플"),
    }
    _kr_symbol = re.compile(r"^\d{6}$")
    _us_symbol = re.compile(r"^[A-Z][A-Z0-9.-]{0,9}$")

    def resolve(
        self,
        *,
        stock_name: str | None,
        symbol: str | None,
        source_text: str,
    ) -> Instrument:
        """Resolve a known name or validate a directly provided broker symbol."""

        if stock_name:
            normalized_name = stock_name.strip().lower()
            known = self._aliases.get(normalized_name)
            if known is not None:
                return known

        if symbol:
            normalized_symbol = symbol.strip().upper()
            symbol_was_spoken = normalized_symbol in source_text.upper()
            if self._kr_symbol.fullmatch(normalized_symbol) and symbol_was_spoken:
                return Instrument(normalized_symbol, stock_name or normalized_symbol)
            if self._us_symbol.fullmatch(normalized_symbol) and symbol_was_spoken:
                known = self._aliases.get(normalized_symbol.lower())
                return known or Instrument(normalized_symbol, stock_name or normalized_symbol)

        raise InstrumentResolutionError(
            "종목을 확실히 찾지 못했습니다. 삼성전자 또는 AAPL처럼 종목명이나 코드를 "
            "다시 말씀해주세요."
        )
