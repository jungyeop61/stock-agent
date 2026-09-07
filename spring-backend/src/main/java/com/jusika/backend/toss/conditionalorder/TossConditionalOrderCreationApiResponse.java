package com.jusika.backend.toss.conditionalorder;

/**
 * 토스증권 조건 주문 생성 응답을 JSON에서 읽기 위한 내부 형식입니다.
 *
 * @param result 생성된 조건 주문의 원본 식별값
 */
record TossConditionalOrderCreationApiResponse(CreationResult result) {

	/** 로그에 조건 주문과 멱등성 식별값이 노출되지 않도록 응답을 가립니다. */
	@Override
	public String toString() {
		return "TossConditionalOrderCreationApiResponse[result=***]";
	}

	/**
	 * 토스증권이 생성 성공 때 반환하는 두 식별값을 보존합니다.
	 *
	 * @param conditionalOrderId 생성된 조건 주문 식별값
	 * @param clientOrderId 요청에 사용한 멱등성 식별값
	 */
	record CreationResult(String conditionalOrderId, String clientOrderId) {

		/** 로그에 두 식별값이 노출되지 않도록 응답을 가립니다. */
		@Override
		public String toString() {
			return "CreationResult[conditionalOrderId=***, clientOrderId=***]";
		}
	}
}
