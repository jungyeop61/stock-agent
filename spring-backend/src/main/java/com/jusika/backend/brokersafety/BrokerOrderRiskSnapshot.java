package com.jusika.backend.brokersafety;

import java.math.BigDecimal;

/**
 * 실행 직전 금융 재검증으로 확정한 한 주문의 위험 한도 검사값입니다.
 *
 * @param quantity 주문 수량이며 금액 주문이면 null
 * @param orderAmount 해당 주문에서 발생할 수 있는 최대 주문금액
 * @param currency 주문금액의 통화 코드
 */
public record BrokerOrderRiskSnapshot(
		BigDecimal quantity,
		BigDecimal orderAmount,
		String currency) {

	/** 로그에 수량과 주문금액이 노출되지 않도록 안전한 설명만 반환합니다. */
	@Override
	public String toString() {
		return "BrokerOrderRiskSnapshot[quantity=***, orderAmount=***, currency="
				+ currency + "]";
	}
}
