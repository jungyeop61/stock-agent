package com.jusika.backend.ordermodification;

import java.math.BigDecimal;

import com.jusika.backend.orderpreview.OrderType;

/**
 * 사용자가 원하는 주문 정정 내용을 미리보기 서비스에 전달합니다.
 *
 * @param accountSeq 원주문의 계좌 식별값
 * @param orderId 정정할 토스증권 원주문 식별값
 * @param orderType 변경할 지정가 또는 시장가 유형
 * @param quantity 변경할 국내 주식 수량이며 미국 주식이면 null
 * @param price 변경할 지정가이며 시장가이면 null
 */
public record OrderModificationPreviewRequest(
		long accountSeq,
		String orderId,
		OrderType orderType,
		BigDecimal quantity,
		BigDecimal price) {

	/** 로그에 주문 식별값과 금융값이 노출되지 않도록 가립니다. */
	@Override
	public String toString() {
		return "OrderModificationPreviewRequest[accountSeq=***, orderId=***"
				+ ", orderType=" + orderType + ", quantity=***, price=***]";
	}
}
