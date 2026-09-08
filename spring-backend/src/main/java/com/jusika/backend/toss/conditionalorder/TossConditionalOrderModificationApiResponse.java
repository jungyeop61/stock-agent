package com.jusika.backend.toss.conditionalorder;

/**
 * 토스증권 조건 주문 정정 응답을 JSON에서 읽기 위한 내부 형식입니다.
 *
 * @param result 기존 주문을 대체하며 새로 발급된 조건 주문 식별값
 */
record TossConditionalOrderModificationApiResponse(ModificationResult result) {

	/** 로그에 새 조건 주문 식별값이 노출되지 않도록 응답 내용을 가립니다. */
	@Override
	public String toString() {
		return "TossConditionalOrderModificationApiResponse[result=***]";
	}

	/**
	 * 토스증권이 정정 성공 후 반환하는 새 조건 주문 식별값입니다.
	 *
	 * @param conditionalOrderId 이후 조회·정정·취소에 사용할 새 식별값
	 */
	record ModificationResult(String conditionalOrderId) {

		/** 로그에 새 조건 주문 식별값이 노출되지 않도록 결과를 가립니다. */
		@Override
		public String toString() {
			return "ModificationResult[conditionalOrderId=***]";
		}
	}
}
