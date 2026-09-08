package com.jusika.backend.toss.marketinfo;

/**
 * 토스증권 환율 API가 공통 응답 형식 안에 담아 보내는 결과를 표현합니다.
 *
 * @param result 두 통화 사이의 환율 결과
 */
public record TossExchangeRateApiResponse(TossExchangeRateResult result) {

	/**
	 * 토스증권 환율 API가 문자열로 반환하는 원본 필드를 표현합니다.
	 *
	 * @param baseCurrency 기준 통화
	 * @param quoteCurrency 상대 통화
	 * @param rate 매수 환율
	 * @param midRate 매매기준율
	 * @param basisPoint 매매기준율 대비 베이시스 포인트
	 * @param rateChangeType 매매기준율 대비 등락 방향
	 * @param validFrom 환율 유효 시작 시각
	 * @param validUntil 환율 유효 종료 시각
	 */
	public record TossExchangeRateResult(
			String baseCurrency,
			String quoteCurrency,
			String rate,
			String midRate,
			String basisPoint,
			String rateChangeType,
			String validFrom,
			String validUntil) {
	}
}
