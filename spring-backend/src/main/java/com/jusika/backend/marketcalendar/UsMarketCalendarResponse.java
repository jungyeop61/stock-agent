package com.jusika.backend.marketcalendar;

/**
 * 조회 기준일과 그 전후 미국 영업일의 시장 운영 정보를 표현합니다.
 *
 * @param today 조회 기준 미국 현지 날짜의 운영 정보
 * @param previousBusinessDay 직전 미국 영업일의 운영 정보
 * @param nextBusinessDay 다음 미국 영업일의 운영 정보
 */
public record UsMarketCalendarResponse(
		UsMarketDayResponse today,
		UsMarketDayResponse previousBusinessDay,
		UsMarketDayResponse nextBusinessDay) {
}
