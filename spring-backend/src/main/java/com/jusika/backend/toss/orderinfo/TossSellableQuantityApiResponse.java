package com.jusika.backend.toss.orderinfo;

/**
 * 토스증권 매도 가능 수량 API의 원본 응답을 표현합니다.
 *
 * @param result 문자열 형태의 매도 가능 수량
 */
public record TossSellableQuantityApiResponse(TossSellableQuantityResult result) {

	/**
	 * 토스증권이 반환한 문자열 형태의 매도 가능 수량을 표현합니다.
	 *
	 * @param sellableQuantity 현재 새 매도 주문에 사용할 수 있는 수량 문자열
	 */
	public record TossSellableQuantityResult(String sellableQuantity) {

		/**
		 * 원본 결과 객체가 로그에 기록되더라도 실제 매도 가능 수량이 노출되지 않도록 가립니다.
		 *
		 * @return 실제 수량이 제거된 결과 설명
		 */
		@Override
		public String toString() {
			return "TossSellableQuantityResult[sellableQuantity=***]";
		}
	}

	/**
	 * 원본 금융정보가 실수로 로그에 기록되지 않도록 수량을 숨깁니다.
	 *
	 * @return 매도 가능 수량이 제거된 응답 설명
	 */
	@Override
	public String toString() {
		return "TossSellableQuantityApiResponse[result=***]";
	}
}
