package com.jusika.backend.exchangerate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 토스증권에서 조회하고 검증한 두 통화 사이의 참고용 환율을 표현합니다.
 *
 * @param baseCurrency 한 단위를 환산할 기준 통화
 * @param quoteCurrency 환산 결과를 표시할 상대 통화
 * @param rate 기준 통화 한 단위에 적용되는 매수 환율
 * @param midRate 은행 간 매매기준율
 * @param basisPoint 매매기준율과 현재 환율의 차이를 나타내는 베이시스 포인트
 * @param rateChangeType 매매기준율 대비 등락 방향
 * @param validFrom 환율 유효 시작 시각
 * @param validUntil 환율 유효 종료 시각
 */
public record ExchangeRateResponse(
		String baseCurrency,
		String quoteCurrency,
		BigDecimal rate,
		BigDecimal midRate,
		BigDecimal basisPoint,
		ExchangeRateChangeType rateChangeType,
		OffsetDateTime validFrom,
		OffsetDateTime validUntil) {
}
