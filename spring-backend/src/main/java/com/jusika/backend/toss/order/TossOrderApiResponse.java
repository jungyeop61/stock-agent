package com.jusika.backend.toss.order;

/**
 * 토스증권 주문 생성 API의 원본 응답을 표현합니다.
 *
 * @param result 토스증권 주문 식별값과 요청 멱등성 식별값
 */
public record TossOrderApiResponse(TossOrderResult result) {

	/**
	 * 토스증권이 반환한 주문 생성 결과를 표현합니다.
	 *
	 * @param orderId 토스증권이 생성한 주문 식별값
	 * @param clientOrderId 요청에서 전달한 멱등성 식별값
	 */
	public record TossOrderResult(String orderId, String clientOrderId) {

		/**
		 * 원본 결과가 로그에 기록되더라도 주문 식별값이 노출되지 않도록 가립니다.
		 *
		 * @return 주문 식별값이 제거된 결과 설명
		 */
		@Override
		public String toString() {
			return "TossOrderResult[orderId=***, clientOrderId=***]";
		}
	}

	/**
	 * 원본 응답이 로그에 기록되더라도 주문 식별값이 노출되지 않도록 가립니다.
	 *
	 * @return 주문 식별값이 제거된 응답 설명
	 */
	@Override
	public String toString() {
		return "TossOrderApiResponse[result=***]";
	}
}
