package com.jusika.backend.toss.conditionalorder;

import java.util.List;

import com.jusika.backend.toss.conditionalorder.TossConditionalOrderApiResponse.TossConditionalOrderResult;

/**
 * 토스증권 조건 주문 목록 응답을 JSON에서 읽기 위한 내부 형식입니다.
 *
 * @param result 조건 주문 목록과 페이지 원본 결과
 */
record TossConditionalOrderListApiResponse(TossConditionalOrderPage result) {

	/**
	 * 로그에 조건 주문과 페이지 커서가 노출되지 않도록 원본 응답을 가립니다.
	 *
	 * @return 민감한 목록 값이 제거된 원본 응답 설명
	 */
	@Override
	public String toString() {
		return "TossConditionalOrderListApiResponse[result=***]";
	}

	/**
	 * 토스증권이 반환하는 한 페이지의 조건 주문과 다음 페이지 정보를 보존합니다.
	 *
	 * @param conditionalOrders 조건 주문 원본 목록
	 * @param nextCursor 다음 페이지 커서
	 * @param hasNext 다음 페이지 존재 여부
	 */
	record TossConditionalOrderPage(
			List<TossConditionalOrderResult> conditionalOrders,
			String nextCursor,
			Boolean hasNext) {

		/**
		 * 로그에 조건 주문과 페이지 커서가 노출되지 않도록 페이지 내용을 가립니다.
		 *
		 * @return 민감한 페이지 값이 제거된 원본 설명
		 */
		@Override
		public String toString() {
			return "TossConditionalOrderPage[conditionalOrders=***, nextCursor=***"
					+ ", hasNext=" + hasNext + "]";
		}
	}
}
