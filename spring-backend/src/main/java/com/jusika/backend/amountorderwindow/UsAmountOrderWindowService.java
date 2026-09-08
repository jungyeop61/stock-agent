package com.jusika.backend.amountorderwindow;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.springframework.stereotype.Service;

import com.jusika.backend.marketcalendar.UsMarketCalendarResponse;
import com.jusika.backend.marketcalendar.UsMarketDayResponse;
import com.jusika.backend.marketcalendar.UsMarketSessionResponse;
import com.jusika.backend.toss.marketinfo.TossUsMarketCalendarClient;

/**
 * 토스증권 미국 장 운영 일정과 현재 시각으로 금액 주문 접수 가능 여부를 판정합니다.
 */
@Service
public class UsAmountOrderWindowService {

	private static final ZoneId NEW_YORK_ZONE = ZoneId.of("America/New_York");
	private static final ZoneOffset KOREA_OFFSET = ZoneOffset.ofHours(9);
	private static final Duration ACCEPTANCE_CLOSING_MARGIN = Duration.ofHours(1);

	private final TossUsMarketCalendarClient marketCalendarClient;
	private final Clock clock;

	/**
	 * 미국 장 운영 일정 조회 기능과 현재 시각을 제공할 시계를 전달받습니다.
	 *
	 * @param marketCalendarClient 토스증권 미국 장 운영 일정 조회 클라이언트
	 * @param clock 시간 판정에 사용할 시스템 시계
	 */
	public UsAmountOrderWindowService(
			TossUsMarketCalendarClient marketCalendarClient,
			Clock clock) {
		this.marketCalendarClient = marketCalendarClient;
		this.clock = clock;
	}

	/**
	 * 현재 미국 현지 날짜의 정규장 시간으로 금액 주문 접수 가능 여부를 조회합니다.
	 * 이 함수는 토스증권 주문 생성 API를 호출하지 않습니다.
	 *
	 * @return 현재 금액 주문 접수 가능 여부와 판단 근거 시간
	 */
	public UsAmountOrderWindowResponse checkCurrentWindow() {
		Instant now = clock.instant();
		LocalDate marketDate = now.atZone(NEW_YORK_ZONE).toLocalDate();
		OffsetDateTime checkedAt = OffsetDateTime.ofInstant(now, KOREA_OFFSET);
		UsMarketCalendarResponse calendar = marketCalendarClient.getMarketCalendar(marketDate);
		UsMarketDayResponse marketDay = requireRequestedMarketDay(calendar, marketDate);

		if (!marketDay.businessDay() || marketDay.regularMarket() == null) {
			return closedMarketResponse(marketDay, checkedAt);
		}
		return calculateOpenMarketResponse(marketDay, checkedAt);
	}

	/**
	 * 캘린더 응답에 요청한 미국 현지 날짜의 기준일 정보가 있는지 확인합니다.
	 *
	 * @param calendar 토스증권에서 검증한 미국 장 운영 일정
	 * @param requestedMarketDate 요청한 미국 현지 날짜
	 * @return 요청 날짜와 일치하는 기준일 운영 정보
	 */
	private UsMarketDayResponse requireRequestedMarketDay(
			UsMarketCalendarResponse calendar,
			LocalDate requestedMarketDate) {
		UsMarketDayResponse marketDay = calendar == null ? null : calendar.today();
		if (marketDay == null || !requestedMarketDate.equals(marketDay.date())) {
			throw new UsAmountOrderWindowException(
					"미국 금액 주문 시간 판정에 필요한 장 운영 정보를 찾지 못했습니다.");
		}
		return marketDay;
	}

	/**
	 * 휴장 또는 정규장 미운영 날짜를 접수 불가 응답으로 만듭니다.
	 *
	 * @param marketDay 판단에 사용한 미국 시장 날짜
	 * @param checkedAt 판단한 한국 표준시 시각
	 * @return 시장 미운영 상태의 금액 주문 시간 응답
	 */
	private UsAmountOrderWindowResponse closedMarketResponse(
			UsMarketDayResponse marketDay,
			OffsetDateTime checkedAt) {
		return new UsAmountOrderWindowResponse(
				marketDay.date(),
				checkedAt,
				marketDay.businessDay(),
				null,
				null,
				null,
				false,
				UsAmountOrderWindowStatus.MARKET_CLOSED);
	}

	/**
	 * 정규장 시작과 종료 1시간 전 사이인지 계산해 현재 접수 구간 상태를 만듭니다.
	 *
	 * @param marketDay 정규장이 포함된 미국 시장 날짜
	 * @param checkedAt 판단한 한국 표준시 시각
	 * @return 현재 접수 가능 여부와 정규장 시간
	 */
	private UsAmountOrderWindowResponse calculateOpenMarketResponse(
			UsMarketDayResponse marketDay,
			OffsetDateTime checkedAt) {
		UsMarketSessionResponse regularMarket = marketDay.regularMarket();
		OffsetDateTime startAt = regularMarket.startTime();
		OffsetDateTime endAt = regularMarket.endTime();
		OffsetDateTime acceptanceEndAt = endAt.minus(ACCEPTANCE_CLOSING_MARGIN);
		if (!startAt.isBefore(acceptanceEndAt)) {
			throw new UsAmountOrderWindowException(
					"미국 금액 주문 접수 구간을 안전하게 계산할 수 없습니다.");
		}

		UsAmountOrderWindowStatus status = determineStatus(checkedAt, startAt, acceptanceEndAt);
		return new UsAmountOrderWindowResponse(
				marketDay.date(),
				checkedAt,
				true,
				startAt,
				endAt,
				acceptanceEndAt,
				status == UsAmountOrderWindowStatus.OPEN,
				status);
	}

	/**
	 * 시작 시각은 포함하고 마감 시각은 제외해 현재 금액 주문 접수 상태를 결정합니다.
	 *
	 * @param checkedAt 판정할 현재 시각
	 * @param startAt 금액 주문 접수 시작 시각
	 * @param acceptanceEndAt 금액 주문 접수 마감 시각
	 * @return 시작 전, 접수 가능 또는 마감 후 상태
	 */
	private UsAmountOrderWindowStatus determineStatus(
			OffsetDateTime checkedAt,
			OffsetDateTime startAt,
			OffsetDateTime acceptanceEndAt) {
		if (checkedAt.isBefore(startAt)) {
			return UsAmountOrderWindowStatus.BEFORE_OPEN;
		}
		if (!checkedAt.isBefore(acceptanceEndAt)) {
			return UsAmountOrderWindowStatus.AFTER_CUTOFF;
		}
		return UsAmountOrderWindowStatus.OPEN;
	}
}
