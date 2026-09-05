package com.jusika.backend.toss.order;

/** 토스증권 주문 취소·정정 API의 공통 원본 응답을 표현합니다. */
record TossOrderOperationApiResponse(TossOrderOperationResult result) {

	/** 로그에 주문 식별값이 노출되지 않도록 가린 설명을 반환합니다. */
	@Override
	public String toString() {
		return "TossOrderOperationApiResponse[result=***]";
	}

	/** 토스증권이 작업 대상으로 확인한 주문 식별값을 담습니다. */
	record TossOrderOperationResult(String orderId) {

		/** 로그에 주문 식별값이 노출되지 않도록 가린 설명을 반환합니다. */
		@Override
		public String toString() {
			return "TossOrderOperationResult[orderId=***]";
		}
	}
}
