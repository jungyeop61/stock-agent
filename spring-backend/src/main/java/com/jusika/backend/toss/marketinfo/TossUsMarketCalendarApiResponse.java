package com.jusika.backend.toss.marketinfo;

/**
 * 토스증권 미국 장 운영 일정 API의 공통 응답 형식을 표현합니다.
 *
 * @param result 조회 기준일과 직전·다음 영업일 결과
 */
public record TossUsMarketCalendarApiResponse(TossUsMarketCalendarResult result) {

	/**
	 * 토스증권이 반환하는 세 날짜의 원본 미국 장 운영 정보를 표현합니다.
	 *
	 * @param today 조회 기준일 정보
	 * @param previousBusinessDay 직전 영업일 정보
	 * @param nextBusinessDay 다음 영업일 정보
	 */
	public record TossUsMarketCalendarResult(
			TossUsMarketDay today,
			TossUsMarketDay previousBusinessDay,
			TossUsMarketDay nextBusinessDay) {
	}

	/**
	 * 미국 현지 날짜와 네 거래 세션의 원본 문자열 시각을 표현합니다.
	 *
	 * @param date 미국 현지 기준 날짜
	 * @param dayMarket 토스증권 데이마켓이며 운영하지 않으면 null
	 * @param preMarket 프리마켓이며 운영하지 않으면 null
	 * @param regularMarket 정규장이며 휴장이면 null
	 * @param afterMarket 애프터마켓이며 운영하지 않으면 null
	 */
	public record TossUsMarketDay(
			String date,
			TossUsMarketSession dayMarket,
			TossUsMarketSession preMarket,
			TossUsMarketSession regularMarket,
			TossUsMarketSession afterMarket) {
	}

	/**
	 * 한 미국 시장 세션의 원본 시작과 종료 시각 문자열을 표현합니다.
	 *
	 * @param startTime 세션 시작 시각
	 * @param endTime 세션 종료 시각
	 */
	public record TossUsMarketSession(String startTime, String endTime) {
	}
}
