package com.jusika.backend.marketcalendar;

import java.time.LocalDate;

/**
 * 미국 현지 날짜 하나의 영업 여부와 세션별 한국 표준시 운영 시간을 표현합니다.
 *
 * @param date 미국 현지 기준 날짜
 * @param businessDay 하나 이상의 시장 세션이 운영되는 영업일인지 여부
 * @param dayMarket 토스증권 데이마켓 운영 시간이며 운영하지 않으면 null
 * @param preMarket 프리마켓 운영 시간이며 운영하지 않으면 null
 * @param regularMarket 정규장 운영 시간이며 휴장이면 null
 * @param afterMarket 애프터마켓 운영 시간이며 운영하지 않으면 null
 */
public record UsMarketDayResponse(
		LocalDate date,
		boolean businessDay,
		UsMarketSessionResponse dayMarket,
		UsMarketSessionResponse preMarket,
		UsMarketSessionResponse regularMarket,
		UsMarketSessionResponse afterMarket) {
}
