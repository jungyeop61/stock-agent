package com.jusika.backend.conditionalorder;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.jusika.backend.brokersafety.BrokerOrderRiskSnapshot;
import com.jusika.backend.orderpreview.OrderSide;
import com.jusika.backend.orderpreview.OrderType;

/**
 * 검증을 마친 OCO 조건 주문을 모의 또는 향후 실제 증권사에 제출할 때 사용하는 요청입니다.
 *
 * @param clientOrderId 중복 생성을 막는 우리 서버의 멱등성 식별값
 * @param symbol 종목 코드
 * @param quantity 두 매도 조건이 공통으로 사용하는 수량
 * @param orderType OCO에서 허용하는 지정가 유형
 * @param expireDate 두 조건의 공통 감시 만료일
 * @param first 현재가보다 높은 첫 번째 매도 조건
 * @param second 현재가보다 낮은 두 번째 매도 조건
 * @param confirmHighValueOrder 1억원 이상 국내 주문을 사용자가 확인했는지 여부
 * @param riskSnapshot 실행 직전 확정한 수량과 최대 주문금액 한도 검사값
 */
public record OcoConditionalOrderSubmissionRequest(
		String clientOrderId,
		String symbol,
		BigDecimal quantity,
		OrderType orderType,
		LocalDate expireDate,
		Condition first,
		Condition second,
		boolean confirmHighValueOrder,
		BrokerOrderRiskSnapshot riskSnapshot) {

	/** 기존 호출 형식을 유지하되 LIVE 한도 검사값은 미지정 상태로 만듭니다. */
	public OcoConditionalOrderSubmissionRequest(
			String clientOrderId,
			String symbol,
			BigDecimal quantity,
			OrderType orderType,
			LocalDate expireDate,
			Condition first,
			Condition second,
			boolean confirmHighValueOrder) {
		this(clientOrderId, symbol, quantity, orderType, expireDate, first, second,
				confirmHighValueOrder, null);
	}

	/** 로그에 식별값과 금융값이 노출되지 않도록 안전한 설명만 반환합니다. */
	@Override
	public String toString() {
		return "OcoConditionalOrderSubmissionRequest[clientOrderId=***, symbol=" + symbol
				+ ", quantity=***, orderType=" + orderType + ", expireDate=" + expireDate
				+ ", first=***, second=***, confirmHighValueOrder="
				+ confirmHighValueOrder + "]";
	}

	/**
	 * OCO를 구성하는 하나의 매도 감시가격과 발동 후 지정가를 표현합니다.
	 *
	 * @param side OCO에서 반드시 SELL인 주문 방향
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
