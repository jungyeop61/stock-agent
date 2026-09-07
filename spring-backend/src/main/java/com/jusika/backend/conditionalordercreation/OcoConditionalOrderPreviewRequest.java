package com.jusika.backend.conditionalordercreation;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.jusika.backend.orderpreview.OrderSide;
import com.jusika.backend.orderpreview.OrderType;

/**
 * OCO 조건 주문 미리보기를 만들기 위해 사용자가 입력하는 값입니다.
 *
 * @param accountSeq 주문에 사용할 계좌 식별값
 * @param symbol 감시할 종목 코드
 * @param quantity 두 조건이 공통으로 사용할 매도 수량
 * @param orderType OCO에서 지정가만 허용되는 주문 유형
 * @param expireDate 두 조건의 공통 감시 만료일
 * @param first 현재가보다 높은 첫 번째 매도 조건
 * @param second 현재가보다 낮은 두 번째 매도 조건
 */
public record OcoConditionalOrderPreviewRequest(
		long accountSeq,
		String symbol,
		BigDecimal quantity,
		OrderType orderType,
		LocalDate expireDate,
		Condition first,
		Condition second) {

	/** 로그에 계좌와 모든 금융값이 노출되지 않도록 안전한 설명만 반환합니다. */
	@Override
	public String toString() {
		return "OcoConditionalOrderPreviewRequest[accountSeq=***, symbol=" + symbol
				+ ", quantity=***, orderType=" + orderType + ", expireDate=" + expireDate
				+ ", first=***, second=***]";
	}

	/**
	 * OCO 미리보기 입력의 한 매도 감시 조건을 표현합니다.
	 *
	 * @param side 반드시 SELL이어야 하는 주문 방향
	 * @param triggerPrice 주문을 발동할 감시가격
	 * @param orderPrice 발동 후 제출할 매도 지정가
	 */
	public record Condition(
			OrderSide side,
			BigDecimal triggerPrice,
			BigDecimal orderPrice) {

		/** 로그에 감시가격과 주문가격이 노출되지 않도록 가립니다. */
		@Override
		public String toString() {
			return "Condition[side=" + side + ", triggerPrice=***, orderPrice=***]";
		}
	}
}
