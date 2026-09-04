package com.jusika.backend.toss.orderinfo;

/**
 * 토스증권 매수 가능 금액 API의 원본 응답을 표현합니다.
 *
 * @param result 통화와 현금 매수 가능 금액
 */
public record TossBuyingPowerApiResponse(TossBuyingPowerResult result) {

	/**
	 * 토스증권이 반환한 통화와 문자열 형태의 현금 매수 가능 금액을 표현합니다.
	 *
	 * @param currency 토스증권이 조회한 통화 코드
	 * @param cashBuyingPower 문자열 형태의 현금 매수 가능 금액
	 */
	public record TossBuyingPowerResult(String currency, String cashBuyingPower) {

		/**
		 * 원본 결과 객체가 로그에 기록되더라도 실제 매수 가능 금액이 노출되지 않도록 가립니다.
		 *
		 * @return 실제 금액이 제거된 결과 설명
		 */
		@Override
		public String toString() {
			return "TossBuyingPowerResult[currency=" + currency + ", cashBuyingPower=***]";
		}
	}

	/**
	 * 원본 금융정보가 실수로 로그에 기록되지 않도록 금액을 숨깁니다.
	 *
	 * @return 매수 가능 금액이 제거된 응답 설명
	 */
	@Override
	public String toString() {
		return "TossBuyingPowerApiResponse[result=***]";
	}
}
