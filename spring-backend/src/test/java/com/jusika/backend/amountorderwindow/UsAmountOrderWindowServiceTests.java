package com.jusika.backend.amountorderwindow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.jusika.backend.marketcalendar.UsMarketCalendarResponse;
import com.jusika.backend.marketcalendar.UsMarketDayResponse;
import com.jusika.backend.marketcalendar.UsMarketSessionResponse;
import com.jusika.backend.toss.marketinfo.TossUsMarketCalendarClient;

/**
 * 가짜 미국 장 운영 일정과 고정 시각으로 금액 주문 접수 가능 시간의 경계를 검사합니다.
 */
class UsAmountOrderWindowServiceTests {

	private FixedUsMarketCalendarClient marketCalendarClient;

	/**
	 * 각 테스트에서 사용할 가짜 미국 장 운영 일정 클라이언트를 준비합니다.
	 */
	@BeforeEach
	void 각_테스트에_필요한_가짜_미국_장_운영_일정을_준비한다() {
		marketCalendarClient = new FixedUsMarketCalendarClient();
		marketCalendarClient.response = 영업일_운영_일정을_만든다(
				"2026-03-25T22:30:00+09:00",
				"2026-03-26T05:00:00+09:00");
	}

	/**
	 * 정규장 시작 전에는 금액 주문을 접수할 수 없다고 판정하는지 검사합니다.
	 */
	@Test
	@DisplayName("미국 정규장 시작 전에는 금액 주문을 막는다")
	void 미국_정규장_시작_전에는_금액_주문을_막는다() {
		UsAmountOrderWindowResponse response = 서비스를_만든다(
				"2026-03-25T22:29:59+09:00").checkCurrentWindow();

		assertThat(response.orderable()).isFalse();
		assertThat(response.status()).isEqualTo(UsAmountOrderWindowStatus.BEFORE_OPEN);
		assertThat(response.orderAcceptanceEndAt())
				.isEqualTo(OffsetDateTime.parse("2026-03-26T04:00:00+09:00"));
	}

	/**
	 * 정규장 시작 시각은 금액 주문 접수 구간에 포함하는지 검사합니다.
	 */
	@Test
	@DisplayName("미국 정규장 시작 시각에는 금액 주문을 허용한다")
	void 미국_정규장_시작_시각에는_금액_주문을_허용한다() {
		UsAmountOrderWindowResponse response = 서비스를_만든다(
				"2026-03-25T22:30:00+09:00").checkCurrentWindow();

		assertThat(response.orderable()).isTrue();
		assertThat(response.status()).isEqualTo(UsAmountOrderWindowStatus.OPEN);
	}

	/**
	 * 정규장 종료 1시간 전의 바로 앞 시각까지 금액 주문을 허용하는지 검사합니다.
	 */
	@Test
	@DisplayName("미국 금액 주문 마감 직전까지 주문을 허용한다")
	void 미국_금액_주문_마감_직전까지_주문을_허용한다() {
		UsAmountOrderWindowResponse response = 서비스를_만든다(
				"2026-03-26T03:59:59+09:00").checkCurrentWindow();

		assertThat(response.orderable()).isTrue();
		assertThat(response.status()).isEqualTo(UsAmountOrderWindowStatus.OPEN);
	}

	/**
	 * 정규장 종료 1시간 전과 정확히 같은 시각부터 금액 주문을 막는지 검사합니다.
	 */
	@Test
	@DisplayName("미국 금액 주문 마감 시각부터 주문을 막는다")
	void 미국_금액_주문_마감_시각부터_주문을_막는다() {
		UsAmountOrderWindowResponse response = 서비스를_만든다(
				"2026-03-26T04:00:00+09:00").checkCurrentWindow();

		assertThat(response.orderable()).isFalse();
		assertThat(response.status()).isEqualTo(UsAmountOrderWindowStatus.AFTER_CUTOFF);
	}

	/**
	 * 네 시장 세션이 모두 없는 휴장일에는 금액 주문을 막는지 검사합니다.
	 */
	@Test
	@DisplayName("미국 시장 휴장일에는 금액 주문을 막는다")
	void 미국_시장_휴장일에는_금액_주문을_막는다() {
		marketCalendarClient.response = 휴장일_운영_일정을_만든다();

		UsAmountOrderWindowResponse response = 서비스를_만든다(
				"2026-03-25T22:30:00+09:00").checkCurrentWindow();

		assertThat(response.businessDay()).isFalse();
		assertThat(response.orderable()).isFalse();
		assertThat(response.regularMarketStartAt()).isNull();
		assertThat(response.orderAcceptanceEndAt()).isNull();
		assertThat(response.status()).isEqualTo(UsAmountOrderWindowStatus.MARKET_CLOSED);
	}

	/**
	 * 한국 새벽에는 한국 날짜가 아니라 뉴욕 현지의 전날 날짜로 캘린더를 조회하는지 검사합니다.
	 */
	@Test
	@DisplayName("한국 새벽에는 뉴욕 현지 날짜로 미국 장 일정을 조회한다")
	void 한국_새벽에는_뉴욕_현지_날짜로_미국_장_일정을_조회한다() {
		UsAmountOrderWindowResponse response = 서비스를_만든다(
				"2026-03-26T03:00:00+09:00").checkCurrentWindow();

		assertThat(marketCalendarClient.requestedDate).isEqualTo(LocalDate.parse("2026-03-25"));
		assertThat(response.marketDate()).isEqualTo(LocalDate.parse("2026-03-25"));
		assertThat(response.checkedAt())
				.isEqualTo(OffsetDateTime.parse("2026-03-26T03:00:00+09:00"));
	}

	/**
	 * 정규장이 1시간 이하라 안전 마감 구간이 없으면 시간 판정을 중단하는지 검사합니다.
	 */
	@Test
	@DisplayName("계산할 수 없는 미국 금액 주문 접수 구간을 거절한다")
	void 계산할_수_없는_미국_금액_주문_접수_구간을_거절한다() {
		marketCalendarClient.response = 영업일_운영_일정을_만든다(
				"2026-03-25T22:30:00+09:00",
				"2026-03-25T23:30:00+09:00");

		assertThatThrownBy(() -> 서비스를_만든다(
				"2026-03-25T22:30:00+09:00").checkCurrentWindow())
				.isInstanceOf(UsAmountOrderWindowException.class)
				.hasMessage("미국 금액 주문 접수 구간을 안전하게 계산할 수 없습니다.");
	}

	/**
	 * 요청한 뉴욕 날짜와 다른 기준일 응답은 시간 판정에 사용하지 않는지 검사합니다.
	 */
	@Test
	@DisplayName("요청 날짜와 다른 미국 장 운영 일정을 거절한다")
	void 요청_날짜와_다른_미국_장_운영_일정을_거절한다() {
		marketCalendarClient.response = new UsMarketCalendarResponse(
				new UsMarketDayResponse(
						LocalDate.parse("2026-03-26"), false, null, null, null, null),
				marketCalendarClient.response.previousBusinessDay(),
				marketCalendarClient.response.nextBusinessDay());

		assertThatThrownBy(() -> 서비스를_만든다(
				"2026-03-25T22:30:00+09:00").checkCurrentWindow())
				.isInstanceOf(UsAmountOrderWindowException.class)
				.hasMessage("미국 금액 주문 시간 판정에 필요한 장 운영 정보를 찾지 못했습니다.");
	}

	/**
	 * 지정한 한국 표준시를 사용하는 금액 주문 시간 판정 서비스를 만듭니다.
	 *
	 * @param checkedAt 판정할 한국 표준시 문자열
	 * @return 고정 시각과 가짜 캘린더를 사용하는 판정 서비스
	 */
	private UsAmountOrderWindowService 서비스를_만든다(String checkedAt) {
		Instant instant = OffsetDateTime.parse(checkedAt).toInstant();
		return new UsAmountOrderWindowService(
				marketCalendarClient,
				Clock.fixed(instant, ZoneOffset.UTC));
	}

	/**
	 * 지정한 정규장 시간이 포함된 기준일과 전후 영업일 일정을 만듭니다.
	 *
	 * @param startAt 정규장 시작 시각
	 * @param endAt 정규장 종료 시각
	 * @return 영업일 상태의 미국 장 운영 일정
	 */
	private UsMarketCalendarResponse 영업일_운영_일정을_만든다(String startAt, String endAt) {
		UsMarketSessionResponse regularMarket = new UsMarketSessionResponse(
				OffsetDateTime.parse(startAt),
				OffsetDateTime.parse(endAt));
		return new UsMarketCalendarResponse(
				new UsMarketDayResponse(
						LocalDate.parse("2026-03-25"), true, null, null, regularMarket, null),
				new UsMarketDayResponse(
						LocalDate.parse("2026-03-24"), true, null, null, regularMarket, null),
				new UsMarketDayResponse(
						LocalDate.parse("2026-03-26"), true, null, null, regularMarket, null));
	}

	/**
	 * 네 세션이 모두 없는 기준일과 정상 전후 영업일 일정을 만듭니다.
	 *
	 * @return 휴장일 상태의 미국 장 운영 일정
	 */
	private UsMarketCalendarResponse 휴장일_운영_일정을_만든다() {
		UsMarketCalendarResponse businessCalendar = 영업일_운영_일정을_만든다(
				"2026-03-25T22:30:00+09:00",
				"2026-03-26T05:00:00+09:00");
		return new UsMarketCalendarResponse(
				new UsMarketDayResponse(
						LocalDate.parse("2026-03-25"), false, null, null, null, null),
				businessCalendar.previousBusinessDay(),
				businessCalendar.nextBusinessDay());
	}

	/**
	 * 준비한 미국 장 운영 일정을 반환하고 요청 날짜를 기록하는 테스트 전용 클라이언트입니다.
	 */
	private static final class FixedUsMarketCalendarClient extends TossUsMarketCalendarClient {

		private UsMarketCalendarResponse response;
		private LocalDate requestedDate;

		/** 실제 REST 구성 없이 테스트용 부모 객체를 초기화합니다. */
		private FixedUsMarketCalendarClient() {
			super(null, null);
		}

		/** 요청 날짜를 기록하고 준비된 미국 장 운영 일정을 반환합니다. */
		@Override
		public UsMarketCalendarResponse getMarketCalendar(LocalDate date) {
			requestedDate = date;
			return response;
		}
	}
}
