import pytest

from jusika_agent.instruments import InstrumentCatalog, InstrumentResolutionError


def test_known_name_uses_deterministic_alias() -> None:
    instrument = InstrumentCatalog().resolve(
        stock_name="삼성전자",
        symbol="999999",
        source_text="삼성전자 다섯 주 사줘",
    )

    assert instrument.symbol == "005930"


def test_rejects_model_symbol_that_user_did_not_say() -> None:
    with pytest.raises(InstrumentResolutionError):
        InstrumentCatalog().resolve(
            stock_name="알 수 없는 회사",
            symbol="005380",
            source_text="알 수 없는 회사 다섯 주 사줘",
        )


def test_accepts_symbol_spoken_directly_by_user() -> None:
    instrument = InstrumentCatalog().resolve(
        stock_name=None,
        symbol="005380",
        source_text="005380 다섯 주 사줘",
    )

    assert instrument.symbol == "005380"
