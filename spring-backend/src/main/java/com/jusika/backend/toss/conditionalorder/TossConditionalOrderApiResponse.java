package com.jusika.backend.toss.conditionalorder;

/**
 * 토스증권 조건 주문 상세 응답을 JSON에서 읽기 위한 내부 형식입니다.
 *
 * @param result 조건 주문 상세 원본 결과
 */
record TossConditionalOrderApiResponse(TossConditionalOrderResult result) {

	/**
	 * 로그에 조건 주문 식별값과 금융값이 노출되지 않도록 원본 응답을 가립니다.
	 *
	 * @return 민감한 조건 주문 값이 제거된 원본 응답 설명
	 */
	@Override
	public String toString() {
		return "TossConditionalOrderApiResponse[result=***]";
	}

	/**
	 * 토스증권이 반환하는 조건 주문 상세 문자열 필드를 그대로 보존합니다.
	 */
	record TossConditionalOrderResult(
			String conditionalOrderId,
			String type,
			String status,
			String symbol,
			String market,
			String quantity,
			String orderType,
			String expireDate,
			TossConditionalOrderCondition first,
			TossConditionalOrderCondition second,
			String createdAt) {

		/**
		 * 로그에 조건 주문 식별값과 금융값이 노출되지 않도록 상세 내용을 가립니다.
		 *
		 * @return 민감한 조건 주문 값이 제거된 원본 상세 설명
		 */
		@Override
		public String toString() {
			return "TossConditionalOrderResult[conditionalOrderId=***, type=" + type
					+ ", status=" + status + ", symbol=" + symbol + ", market=" + market
					+ ", quantity=***, orderType=" + orderType + ", expireDate=" + expireDate
					+ ", first=***, second=***, createdAt=" + createdAt + "]";
		}
	}

	/**
	 * 토스증권이 반환하는 개별 감시 조건 문자열을 그대로 보존합니다.
	 */
	record TossConditionalOrderCondition(
			String type,
			String status,
			String triggerPrice,
			String targetProfitRate,
			String orderPrice,
			String triggeredOrderId) {

		/**
		 * 로그에 가격과 발동 주문 식별값이 노출되지 않도록 감시 조건을 가립니다.
		 *
		 * @return 민감한 감시 조건 값이 제거된 원본 설명
		 */
		@Override
		public String toString() {
			return "TossConditionalOrderCondition[type=" + type + ", status=" + status
					+ ", triggerPrice=***, targetProfitRate=***, orderPrice=***"
					+ ", triggeredOrderId=***]";
		}
	}
}
