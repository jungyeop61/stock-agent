package com.jusika.backend.marketcalendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.jusika.backend.toss.marketinfo.TossUsMarketCalendarClient;

/**
 * 미국 장 운영 일정 HTTP 주소가 날짜를 변환해 읽기 전용 클라이언트에 전달하는지 검사합니다.
 */
class UsMarketCalendarControllerTests {

	private RecordingUsMarketCalendarClient recordingClient;
	private MockMvc mockMvc;

	/**
	 * 각 테스트에서 실제 토스증권 호출 없이 컨트롤러만 검사할 환경을 준비합니다.
	 */
	@BeforeEach
	void 각_테스트에_필요한_미국_장_운영_컨트롤러를_준비한다() {
		recordingClient = new RecordingUsMarketCalendarClient();
		mockMvc = MockMvcBuilders
				.standaloneSetup(new UsMarketCalendarController(recordingClient))
				.build();
	}

	/**
	 * 미국 현지 날짜를 클라이언트에 전달하고 휴장 여부와 세션 시각을 JSON으로 반환하는지 검사합니다.
	 */
	@Test
	@DisplayName("지정 날짜의 미국 장 운영 일정을 HTTP로 조회한다")
	void 지정_날짜의_미국_장_운영_일정을_HTTP로_조회한다() throws Exception {
		recordingClient.response = 정상_미국_장_운영_일정을_만든다();

		mockMvc.perform(get("/api/market/us/calendar")
						.param("date", "2026-03-25"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.today.date").value("2026-03-25"))
				.andExpect(jsonPath("$.today.businessDay").value(true))
				.andExpect(jsonPath("$.today.regularMarket.startTime")
						.value("2026-03-25T22:30:00+09:00"))
				.andExpect(jsonPath("$.previousBusinessDay.date").value("2026-03-24"))
				.andExpect(jsonPath("$.nextBusinessDay.date").value("2026-03-26"));

		assertThat(recordingClient.callCount).isEqualTo(1);
		assertThat(recordingClient.date).isEqualTo(LocalDate.parse("2026-03-25"));
	}

	/**
	 * 날짜 쿼리가 없으면 현재 기준일 조회를 위해 null을 전달하는지 검사합니다.
	 */
	@Test
	@DisplayName("현재 기준 미국 장 운영 일정을 HTTP로 조회한다")
	void 현재_기준_미국_장_운영_일정을_HTTP로_조회한다() throws Exception {
		recordingClient.response = 정상_미국_장_운영_일정을_만든다();

		mockMvc.perform(get("/api/market/us/calendar"))
				.andExpect(status().isOk());

		assertThat(recordingClient.callCount).isEqualTo(1);
		assertThat(recordingClient.date).isNull();
	}

	/**
	 * 형식이 잘못된 날짜는 클라이언트를 호출하기 전에 HTTP 400으로 거절하는지 검사합니다.
	 */
	@Test
	@DisplayName("잘못된 미국 장 조회 날짜를 HTTP 400으로 거절한다")
	void 잘못된_미국_장_조회_날짜를_HTTP_400으로_거절한다() throws Exception {
		mockMvc.perform(get("/api/market/us/calendar")
						.param("date", "2026-13-40"))
				.andExpect(status().isBadRequest());

		assertThat(recordingClient.callCount).isZero();
	}

	/**
	 * 컨트롤러 응답에 사용할 정상 미국 장 운영 일정을 만듭니다.
	 *
	 * @return 기준일과 직전·다음 영업일이 포함된 응답
	 */
	private UsMarketCalendarResponse 정상_미국_장_운영_일정을_만든다() {
		UsMarketSessionResponse regularMarket = new UsMarketSessionResponse(
				OffsetDateTime.parse("2026-03-25T22:30:00+09:00"),
				OffsetDateTime.parse("2026-03-26T05:00:00+09:00"));
		return new UsMarketCalendarResponse(
				new UsMarketDayResponse(
						LocalDate.parse("2026-03-25"),
						true,
						null,
						null,
						regularMarket,
						null),
				new UsMarketDayResponse(
						LocalDate.parse("2026-03-24"),
						true,
						null,
						null,
						regularMarket,
						null),
				new UsMarketDayResponse(
						LocalDate.parse("2026-03-26"),
						true,
						null,
						null,
						regularMarket,
						null));
	}

	/**
	 * 실제 REST 클라이언트 없이 컨트롤러가 전달한 날짜를 기록하는 테스트 전용 클라이언트입니다.
	 */
	private static final class RecordingUsMarketCalendarClient extends TossUsMarketCalendarClient {

		private UsMarketCalendarResponse response;
		private LocalDate date;
		private int callCount;

		/** 실제 REST 구성 없이 기록용 부모 객체를 초기화합니다. */
		private RecordingUsMarketCalendarClient() {
			super(null, null);
		}

		/** 요청 날짜를 기록하고 준비된 미국 장 운영 일정을 반환합니다. */
		@Override
		public UsMarketCalendarResponse getMarketCalendar(LocalDate date) {
			callCount++;
			this.date = date;
			return response;
		}
	}
}
