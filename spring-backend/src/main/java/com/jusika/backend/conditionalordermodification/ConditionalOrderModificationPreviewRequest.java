package com.jusika.backend.conditionalordermodification;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.jusika.backend.conditionalorder.ConditionalOrderType;
import com.jusika.backend.orderpreview.OrderSide;
import com.jusika.backend.orderpreview.OrderType;

/**
 * 조건 주문 정정 미리보기에 필요한 대상과 정정 후 전체 구성을 전달합니다.
 *
 * @param accountSeq 정정 대상 계좌 식별값
 * @param conditionalOrderId 정정할 기존 조건 주문 식별값
 * @param type 정정 후 조건 주문 유형
 * @param quantity 정정 후 공통 주문 수량
 * @param orderType 정정 후 지정가 또는 시장가 유형
 * @param expireDate 정정 후 조건 감시 만료일
 * @param first 정정 후 첫 번째 필수 조건
 * @param second 정정 후 두 번째 조건이며 SINGLE이면 null
 */
public record ConditionalOrderModificationPreviewRequest(
		long accountSeq,
		String conditionalOrderId,
		ConditionalOrderType type,
		BigDecimal quantity,
		OrderType orderType,
		LocalDate expireDate,
		Condition first,
		Condition second) {

	/** 로그에 계좌·식별값·금융값이 노출되지 않도록 안전한 설명만 반환합니다. */
	@Override
	public String toString() {
		return "ConditionalOrderModificationPreviewRequest[accountSeq=***"
				+ ", conditionalOrderId=***, type=" + type + ", quantity=***"
				+ ", orderType=" + orderType + ", expireDate=" + expireDate
				+ ", first=***, second=***]";
	}

	/**
	 * 정정 후 조건 주문의 한 감시 조건을 표현합니다.
	 *
	 * @param side 조건 충족 시 제출할 주문 방향
	 * @param triggerPrice 주문을 발동할 감시가격
	 * @param orderPrice 발동 후 제출할 지정가이며 시장가이면 null
	 */
	public record Condition(OrderSide side, BigDecimal triggerPrice, BigDecimal orderPrice) {

		/** 로그에 가격이 노출되지 않도록 안전한 설명만 반환합니다. */
		@Override
		public String toString() {
			return "Condition[side=" + side + ", triggerPrice=***, orderPrice=***]";
		}
	}
}
