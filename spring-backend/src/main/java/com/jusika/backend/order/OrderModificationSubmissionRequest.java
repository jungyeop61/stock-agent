package com.jusika.backend.order;

import java.math.BigDecimal;

import com.jusika.backend.brokersafety.BrokerOrderRiskSnapshot;
import com.jusika.backend.orderpreview.OrderType;

/**
 * 최종 검증을 마친 주문 정정 내용을 증권사 경계에 전달합니다.
 *
 * @param currency 원주문의 거래 통화
 * @param orderType 정정할 지정가 또는 시장가 유형
 * @param quantity 정정할 국내 주식 수량이며 미국 주식이면 null
 * @param price 정정할 지정가이며 시장가이면 null
 * @param confirmHighValueOrder 국내 1억원 이상 주문 확인 여부
 * @param riskSnapshot 실행 직전 확정한 수량과 주문금액 한도 검사값
 * @param symbol LIVE 종목 허용 목록을 재검사할 원주문 종목 코드
 */
public record OrderModificationSubmissionRequest(
		String currency,
		OrderType orderType,
		BigDecimal quantity,
		BigDecimal price,
		boolean confirmHighValueOrder,
		BrokerOrderRiskSnapshot riskSnapshot,
		String symbol) {

	/** 기존 호출 형식을 유지하되 LIVE 한도 검사값은 미지정 상태로 만듭니다. */
	public OrderModificationSubmissionRequest(
			String currency,
			OrderType orderType,
			BigDecimal quantity,
			BigDecimal price,
			boolean confirmHighValueOrder) {
		this(currency, orderType, quantity, price, confirmHighValueOrder, null, null);
	}

	/** 기존 한도 포함 호출 형식을 유지하되 LIVE 종목 검사값은 미지정 상태로 만듭니다. */
	public OrderModificationSubmissionRequest(
			String currency,
			OrderType orderType,
			BigDecimal quantity,
			BigDecimal price,
			boolean confirmHighValueOrder,
			BrokerOrderRiskSnapshot riskSnapshot) {
		this(currency, orderType, quantity, price, confirmHighValueOrder, riskSnapshot, null);
	}

	/** 로그에 정정 수량과 가격이 노출되지 않도록 안전한 설명만 반환합니다. */
	@Override
	public String toString() {
		return "OrderModificationSubmissionRequest[currency=" + currency
				+ ", orderType=" + orderType + ", quantity=***, price=***"
				+ ", confirmHighValueOrder=" + confirmHighValueOrder + "]";
	}
}
