package com.jusika.backend.amountorderwindow;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 현재 미국 주식 금액 주문 접수 가능 여부와 판단에 사용한 시장 시간을 표현합니다.
 *
 * @param marketDate 판단에 사용한 미국 현지 장 날짜
 * @param checkedAt 판단한 한국 표준시 시각
 * @param businessDay 미국 시장 영업일인지 여부
 * @param regularMarketStartAt 정규장 시작 시각이며 휴장이면 null
 * @param regularMarketEndAt 정규장 종료 시각이며 휴장이면 null
 * @param orderAcceptanceEndAt 금액 주문 접수 마감 시각이며 휴장이면 null
 * @param orderable 현재 시각에 금액 주문을 접수할 수 있는지 여부
 * @param status 현재 시각의 금액 주문 접수 구간 상태
 */
public record UsAmountOrderWindowResponse(
		LocalDate marketDate,
		OffsetDateTime checkedAt,
		boolean businessDay,
		OffsetDateTime regularMarketStartAt,
		OffsetDateTime regularMarketEndAt,
		OffsetDateTime orderAcceptanceEndAt,
		boolean orderable,
		UsAmountOrderWindowStatus status) {
}
