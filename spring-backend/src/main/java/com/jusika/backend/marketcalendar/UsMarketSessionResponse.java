package com.jusika.backend.marketcalendar;

import java.time.OffsetDateTime;

/**
 * 미국 시장의 한 거래 세션 시작과 종료 시각을 한국 표준시 기준으로 표현합니다.
 *
 * @param startTime 세션 시작 시각
 * @param endTime 세션 종료 시각
 */
public record UsMarketSessionResponse(
		OffsetDateTime startTime,
		OffsetDateTime endTime) {
}
