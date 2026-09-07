package com.jusika.backend.toss.conditionalorder;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 토스증권 조건 주문 생성 요청의 JSON 구조를 외부 API 형식과 분리해 보존합니다.
 */
final class TossConditionalOrderApiRequests {

	/** 외부에서 요청 형식 객체를 직접 만들지 못하게 막습니다. */
	private TossConditionalOrderApiRequests() {
	}

	/**
	 * 단일 또는 OCO 조건 주문 생성 본문의 필수값과 선택값을 표현합니다.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record CreateRequest(
			String symbol,
			String type,
			String quantity,
			String orderType,
			String clientOrderId,
			String expireDate,
			ConditionRequest first,
			ConditionRequest second,
			boolean confirmHighValueOrder) {

		/** 로그에 금융값과 멱등성 식별값이 노출되지 않도록 요청 내용을 가립니다. */
		@Override
		public String toString() {
			return "CreateRequest[symbol=" + symbol + ", type=" + type
					+ ", quantity=***, orderType=" + orderType + ", clientOrderId=***"
					+ ", expireDate=" + expireDate + ", first=***, second=***"
					+ ", confirmHighValueOrder=" + confirmHighValueOrder + "]";
		}
	}

	/**
	 * 단일 또는 OCO 조건 주문의 한 가격 감시 조건을 표현합니다.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record ConditionRequest(
			String orderSide,
			String triggerPrice,
			String orderPrice) {

		/** 로그에 감시가격과 주문가격이 노출되지 않도록 요청 내용을 가립니다. */
		@Override
		public String toString() {
			return "ConditionRequest[orderSide=" + orderSide
					+ ", triggerPrice=***, orderPrice=***]";
		}
	}
}
