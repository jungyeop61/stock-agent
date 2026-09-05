package com.jusika.backend.toss.orderhistory;

import java.util.List;

import com.jusika.backend.toss.orderhistory.TossOrderHistoryApiResponse.TossOrderResult;

/**
 * 토스증권 주문 목록 응답 봉투를 JSON에서 읽기 위한 내부 형식입니다.
 *
 * @param result 주문 목록과 페이지 원본 결과
 */
record TossOrderListApiResponse(TossOrderPage result) {

	/**
	 * 로그에 주문과 페이지 커서가 노출되지 않도록 원본 응답 내용을 가립니다.
	 *
	 * @return 민감한 주문 값이 제거된 원본 응답 설명
	 */
	@Override
	public String toString() {
		return "TossOrderListApiResponse[result=***]";
	}

	/**
	 * 토스증권이 반환하는 한 페이지의 주문과 다음 페이지 정보를 보존합니다.
	 *
	 * @param orders 주문 원본 목록
	 * @param nextCursor 다음 페이지 커서
	 * @param hasNext 다음 페이지 존재 여부
	 */
	record TossOrderPage(
			List<TossOrderResult> orders,
			String nextCursor,
			Boolean hasNext) {

		/**
		 * 로그에 주문과 페이지 커서가 노출되지 않도록 원본 페이지 내용을 가립니다.
		 *
		 * @return 민감한 주문 값이 제거된 원본 페이지 설명
		 */
		@Override
		public String toString() {
			return "TossOrderPage[orders=***, nextCursor=***, hasNext=" + hasNext + "]";
		}
	}
}
