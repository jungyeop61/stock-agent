package com.jusika.backend.order;

import java.math.BigDecimal;

import com.jusika.backend.orderpreview.OrderType;

/**
 * 최종 검증을 마친 주문 정정 내용을 증권사 경계에 전달합니다.
 *
 * @param currency 원주문의 거래 통화
 * @param orderType 정정할 지정가 또는 시장가 유형
 * @param quantity 정정할 국내 주식 수량이며 미국 주식이면 null
 * @param price 정정할 지정가이며 시장가이면 null
 * @param confirmHighValueOrder 국내 1억원 이상 주문 확인 여부
 */
public record OrderModificationSubmissionRequest(
		String currency,
		OrderType orderType,
		BigDecimal quantity,
		BigDecimal price,
		boolean confirmHighValueOrder) {

	/** 로그에 정정 수량과 가격이 노출되지 않도록 안전한 설명만 반환합니다. */
	@Override
	public String toString() {
		return "OrderModificationSubmissionRequest[currency=" + currency
				+ ", orderType=" + orderType + ", quantity=***, price=***"
				+ ", confirmHighValueOrder=" + confirmHighValueOrder + "]";
	}
}
