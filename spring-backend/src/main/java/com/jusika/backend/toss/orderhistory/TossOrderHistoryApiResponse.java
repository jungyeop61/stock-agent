package com.jusika.backend.toss.orderhistory;

/**
 * 토스증권 주문 상세 조회의 공통 응답 봉투를 JSON에서 읽기 위한 내부 형식입니다.
 *
 * @param result 주문 상세 원본 결과
 */
record TossOrderHistoryApiResponse(TossOrderResult result) {

	/**
	 * 로그에 주문 식별값과 금융값이 노출되지 않도록 원본 응답 내용을 가립니다.
	 *
	 * @return 민감한 주문 값이 제거된 원본 응답 설명
	 */
	@Override
	public String toString() {
		return "TossOrderHistoryApiResponse[result=***]";
	}

	/**
	 * 토스증권이 반환하는 주문 상세 문자열 필드를 그대로 보존합니다.
	 */
	record TossOrderResult(
			String orderId,
			String symbol,
			String side,
			String orderType,
			String timeInForce,
			String status,
			String price,
			String quantity,
			String orderAmount,
			String currency,
			String orderedAt,
			String canceledAt,
			TossExecution execution) {

		/**
		 * 로그에 주문 식별값과 금융값이 노출되지 않도록 원본 주문 내용을 가립니다.
		 *
		 * @return 민감한 주문 값이 제거된 원본 주문 설명
		 */
		@Override
		public String toString() {
			return "TossOrderResult[orderId=***, symbol=" + symbol + ", side=" + side
					+ ", orderType=" + orderType + ", timeInForce=" + timeInForce
					+ ", status=" + status + ", price=***, quantity=***, orderAmount=***"
					+ ", currency=" + currency + ", orderedAt=" + orderedAt
					+ ", canceledAt=" + canceledAt + ", execution=***]";
		}
	}

	/**
	 * 토스증권이 반환하는 누적 체결 결과 문자열을 그대로 보존합니다.
	 */
	record TossExecution(
			String filledQuantity,
			String averageFilledPrice,
			String filledAmount,
			String commission,
			String tax,
			String filledAt,
			String settlementDate) {

		/**
		 * 로그에 누적 체결 금융값이 노출되지 않도록 원본 체결 내용을 가립니다.
		 *
		 * @return 금융값이 제거된 원본 체결 설명
		 */
		@Override
		public String toString() {
			return "TossExecution[filledQuantity=***, averageFilledPrice=***"
					+ ", filledAmount=***, commission=***, tax=***"
					+ ", filledAt=" + filledAt + ", settlementDate=" + settlementDate + "]";
		}
	}
}
