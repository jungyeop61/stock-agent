package com.jusika.backend.sellablequantity;

import java.math.BigDecimal;

/**
 * 우리 서버가 사용자에게 반환하는 종목별 매도 가능 수량을 표현합니다.
 *
 * @param accountSeq 조회에 사용한 계좌 식별값
 * @param symbol 조회한 국내 또는 해외 주식 종목 코드
 * @param sellableQuantity 현재 새 매도 주문에 사용할 수 있는 수량
 */
public record SellableQuantityResponse(
		long accountSeq,
		String symbol,
		BigDecimal sellableQuantity) {
}
