package com.jusika.backend.buyingpower;

import java.math.BigDecimal;

/**
 * 우리 서버가 사용자에게 반환하는 통화별 현금 매수 가능 금액을 표현합니다.
 *
 * @param accountSeq 조회에 사용한 계좌 식별값
 * @param currency 조회한 통화 코드
 * @param cashBuyingPower 미수 없이 순수 현금으로 매수할 수 있는 금액
 */
public record BuyingPowerResponse(
		long accountSeq,
		String currency,
		BigDecimal cashBuyingPower) {
}
