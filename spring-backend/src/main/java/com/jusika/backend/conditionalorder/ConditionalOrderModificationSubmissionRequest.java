package com.jusika.backend.conditionalorder;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.jusika.backend.brokersafety.BrokerOrderRiskSnapshot;
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
 * @param riskSnapshot 실행 직전 확정한 수량과 최대 주문금액 한도 검사값
 * @param symbol LIVE 종목 허용 목록을 재검사할 원조건 주문 종목 코드
 */
public record ConditionalOrderModificationSubmissionRequest(
		ConditionalOrderType type,
		BigDecimal quantity,
		OrderType orderType,
		LocalDate expireDate,
		Condition first,
		Condition second,
		boolean confirmHighValueOrder,
		BrokerOrderRiskSnapshot riskSnapshot,
		String symbol) {

	/** 기존 호출 형식을 유지하되 LIVE 한도 검사값은 미지정 상태로 만듭니다. */
	public ConditionalOrderModificationSubmissionRequest(
			ConditionalOrderType type,
			BigDecimal quantity,
			OrderType orderType,
			LocalDate expireDate,
			Condition first,
			Condition second,
			boolean confirmHighValueOrder) {
		this(type, quantity, orderType, expireDate, first, second,
				confirmHighValueOrder, null, null);
	}

	/** 기존 한도 포함 호출 형식을 유지하되 LIVE 종목 검사값은 미지정 상태로 만듭니다. */
	public ConditionalOrderModificationSubmissionRequest(
			ConditionalOrderType type,
			BigDecimal quantity,
			OrderType orderType,
			LocalDate expireDate,
			Condition first,
			Condition second,
			boolean confirmHighValueOrder,
			BrokerOrderRiskSnapshot riskSnapshot) {
		this(type, quantity, orderType, expireDate, first, second,
				confirmHighValueOrder, riskSnapshot, null);
	}

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
