package com.jusika.backend.conditionalorder;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.jusika.backend.orderpreview.OrderSide;
import com.jusika.backend.orderpreview.OrderType;

/**
 * 최종 검증을 마친 조건 주문 정정의 새 전체 구성을 증권사 경계에 전달합니다.
 *
 * @param type 정정 후 조건 주문 유형
 * @param quantity 모든 감시 조건이 공통으로 사용할 수량
 * @param orderType 지정가 또는 시장가 유형
 * @param expireDate 정정 후 조건 감시 만료일
 * @param first 첫 번째 필수 감시 조건
 * @param second OCO와 OTO의 두 번째 조건이며 SINGLE이면 null
 * @param confirmHighValueOrder 1억원 이상 국내 주문을 사용자가 승인했는지 여부
 */
public record ConditionalOrderModificationSubmissionRequest(
		ConditionalOrderType type,
		BigDecimal quantity,
		OrderType orderType,
		LocalDate expireDate,
		Condition first,
		Condition second,
		boolean confirmHighValueOrder) {

	/** 로그에 수량과 가격이 노출되지 않도록 안전한 설명만 반환합니다. */
	@Override
	public String toString() {
		return "ConditionalOrderModificationSubmissionRequest[type=" + type
				+ ", quantity=***, orderType=" + orderType + ", expireDate=" + expireDate
				+ ", first=***, second=***, confirmHighValueOrder="
				+ confirmHighValueOrder + "]";
	}

	/**
	 * 정정 후 조건 주문을 구성하는 한 감시가격과 주문 방향을 표현합니다.
	 *
	 * @param side 조건 충족 시 제출할 매수 또는 매도 방향
	 * @param triggerPrice 주문을 발동할 감시가격
	 * @param orderPrice 발동 후 제출할 지정가이며 시장가이면 null
	 */
	public record Condition(
			OrderSide side,
			BigDecimal triggerPrice,
			BigDecimal orderPrice) {

		/** 로그에 감시가격과 주문가격이 노출되지 않도록 안전한 설명만 반환합니다. */
		@Override
		public String toString() {
			return "Condition[side=" + side + ", triggerPrice=***, orderPrice=***]";
		}
	}
}
