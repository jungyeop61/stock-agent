package com.jusika.backend.toss.order;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jusika.backend.order.OrderTimeInForce;
import com.jusika.backend.orderpreview.OrderSide;
import com.jusika.backend.orderpreview.OrderType;

/**
 * 토스증권 주문 생성·정정 API에 문자열 소수 형식으로 보낼 내부 요청 객체를 모아 둡니다.
 */
final class TossOrderApiRequests {

	/**
	 * 외부에서 내부 요청 객체 모음 클래스를 만들지 못하게 합니다.
	 */
	private TossOrderApiRequests() {
	}

	/**
	 * 토스증권 수량 기반 주문의 실제 JSON 필드를 표현합니다.
	 *
	 * @param clientOrderId 멱등성 식별값
	 * @param symbol 정규화된 종목 코드
	 * @param side 매수 또는 매도 방향
	 * @param orderType 지정가 또는 시장가 유형
	 * @param timeInForce 주문 유효 조건
	 * @param quantity 문자열 형태의 주문 수량
	 * @param price 문자열 형태의 지정가이며 시장가이면 null
	 * @param confirmHighValueOrder 고액 주문 확인 여부
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record QuantityRequest(
			String clientOrderId,
			String symbol,
			OrderSide side,
			OrderType orderType,
			OrderTimeInForce timeInForce,
			String quantity,
			String price,
			boolean confirmHighValueOrder) {

		/**
		 * 내부 요청이 로그에 기록되더라도 주문 수량과 가격이 노출되지 않도록 가립니다.
		 *
		 * @return 금융정보가 제거된 내부 요청 설명
		 */
		@Override
		public String toString() {
			return "QuantityRequest[clientOrderId=***, symbol=" + symbol
					+ ", side=" + side + ", orderType=" + orderType
					+ ", timeInForce=" + timeInForce
					+ ", quantity=***, price=***, confirmHighValueOrder="
					+ confirmHighValueOrder + "]";
		}
	}

	/**
	 * 토스증권 미국 주식 금액 기반 시장가 주문의 실제 JSON 필드를 표현합니다.
	 *
	 * @param clientOrderId 멱등성 식별값
	 * @param symbol 정규화된 미국 종목 코드
	 * @param side 매수 또는 매도 방향
	 * @param orderType 금액 주문에 고정된 시장가 유형
	 * @param orderAmount 문자열 형태의 달러 주문 금액
	 * @param confirmHighValueOrder 고액 주문 확인 여부
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record AmountRequest(
			String clientOrderId,
			String symbol,
			OrderSide side,
			OrderType orderType,
			String orderAmount,
			boolean confirmHighValueOrder) {

		/**
		 * 내부 요청이 로그에 기록되더라도 주문 금액이 노출되지 않도록 가립니다.
		 *
		 * @return 금융정보가 제거된 내부 요청 설명
		 */
		@Override
		public String toString() {
			return "AmountRequest[clientOrderId=***, symbol=" + symbol
					+ ", side=" + side + ", orderType=" + orderType
					+ ", orderAmount=***, confirmHighValueOrder="
					+ confirmHighValueOrder + "]";
		}
	}

	/**
	 * 토스증권 주문 정정 API의 실제 JSON 필드를 표현합니다.
	 *
	 * @param orderType 변경할 지정가 또는 시장가 유형
	 * @param quantity 문자열 형태의 국내 정정 수량이며 미국 주식이면 null
	 * @param price 문자열 형태의 정정 지정가이며 시장가이면 null
	 * @param confirmHighValueOrder 국내 1억원 이상 정정 확인 여부
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record ModificationRequest(
			OrderType orderType,
			String quantity,
			String price,
			boolean confirmHighValueOrder) {

		/** 로그에 정정 수량과 가격이 노출되지 않도록 가립니다. */
		@Override
		public String toString() {
			return "ModificationRequest[orderType=" + orderType
					+ ", quantity=***, price=***, confirmHighValueOrder="
					+ confirmHighValueOrder + "]";
		}
	}
}
