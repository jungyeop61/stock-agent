package com.jusika.backend.exchangerate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.jusika.backend.toss.marketinfo.TossExchangeRateClient;

/**
 * 환율 HTTP 주소가 쿼리를 올바른 자료형으로 변환해 읽기 전용 클라이언트에 전달하는지 검사합니다.
 */
class ExchangeRateControllerTests {

	private RecordingExchangeRateClient recordingClient;
	private MockMvc mockMvc;

	/**
	 * 각 테스트에서 실제 토스증권 호출 없이 컨트롤러만 검사할 환경을 준비합니다.
	 */
	@BeforeEach
	void 각_테스트에_필요한_환율_컨트롤러를_준비한다() {
		recordingClient = new RecordingExchangeRateClient();
		mockMvc = MockMvcBuilders
				.standaloneSetup(new ExchangeRateController(recordingClient))
				.build();
	}

	/**
	 * 현재 환율 조회 쿼리를 클라이언트에 전달하고 숫자와 시각을 JSON으로 반환하는지 검사합니다.
	 */
	@Test
	@DisplayName("현재 환율 HTTP 요청을 조회한다")
	void 현재_환율_HTTP_요청을_조회한다() throws Exception {
		recordingClient.response = 정상_환율을_만든다();

		mockMvc.perform(get("/api/market/exchange-rate")
						.param("baseCurrency", "USD")
						.param("quoteCurrency", "KRW"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.baseCurrency").value("USD"))
				.andExpect(jsonPath("$.quoteCurrency").value("KRW"))
				.andExpect(jsonPath("$.rate").value(1380.5))
				.andExpect(jsonPath("$.rateChangeType").value("UP"))
				.andExpect(jsonPath("$.validUntil").value("2026-09-08T09:31:00+09:00"));

		assertThat(recordingClient.callCount).isEqualTo(1);
		assertThat(recordingClient.baseCurrency).isEqualTo("USD");
		assertThat(recordingClient.quoteCurrency).isEqualTo("KRW");
		assertThat(recordingClient.dateTime).isNull();
	}

	/**
	 * ISO 8601 시각 쿼리를 OffsetDateTime으로 변환해 클라이언트에 전달하는지 검사합니다.
	 */
	@Test
	@DisplayName("지정 시점 환율 HTTP 요청의 시각을 변환한다")
	void 지정_시점_환율_HTTP_요청의_시각을_변환한다() throws Exception {
		recordingClient.response = 정상_환율을_만든다();
		String dateTime = "2026-09-08T09:30:00+09:00";

		mockMvc.perform(get("/api/market/exchange-rate")
						.param("baseCurrency", "USD")
						.param("quoteCurrency", "KRW")
						.param("dateTime", dateTime))
				.andExpect(status().isOk());

		assertThat(recordingClient.dateTime).isEqualTo(OffsetDateTime.parse(dateTime));
	}

	/**
	 * 형식이 잘못된 시각은 클라이언트를 호출하기 전에 HTTP 400으로 거절하는지 검사합니다.
	 */
	@Test
	@DisplayName("잘못된 환율 조회 시각을 HTTP 400으로 거절한다")
	void 잘못된_환율_조회_시각을_HTTP_400으로_거절한다() throws Exception {
		mockMvc.perform(get("/api/market/exchange-rate")
						.param("baseCurrency", "USD")
						.param("quoteCurrency", "KRW")
						.param("dateTime", "잘못된-시각"))
				.andExpect(status().isBadRequest());

		assertThat(recordingClient.callCount).isZero();
	}

	/**
	 * 컨트롤러 응답에 사용할 정상 환율을 만듭니다.
	 *
	 * @return 숫자와 유효시간이 포함된 환율 응답
	 */
	private ExchangeRateResponse 정상_환율을_만든다() {
		return new ExchangeRateResponse(
				"USD",
				"KRW",
				new BigDecimal("1380.5"),
				new BigDecimal("1375"),
				new BigDecimal("40"),
				ExchangeRateChangeType.UP,
				OffsetDateTime.parse("2026-09-08T09:30:00+09:00"),
				OffsetDateTime.parse("2026-09-08T09:31:00+09:00"));
	}

	/**
	 * 실제 REST 클라이언트 없이 컨트롤러가 전달한 인수를 기록하는 테스트 전용 클라이언트입니다.
	 */
	private static final class RecordingExchangeRateClient extends TossExchangeRateClient {

		private ExchangeRateResponse response;
		private int callCount;
		private String baseCurrency;
		private String quoteCurrency;
		private OffsetDateTime dateTime;

		/**
		 * 실제 REST 클라이언트와 토큰 공급자 없이 기록용 부모 객체를 초기화합니다.
		 */
		private RecordingExchangeRateClient() {
			super(null, null);
		}

		/**
		 * 컨트롤러가 전달한 환율 조회 인수를 기록하고 준비된 응답을 반환합니다.
		 *
		 * @param baseCurrency 기준 통화
		 * @param quoteCurrency 상대 통화
		 * @param dateTime 선택 조회 시각
		 * @return 테스트가 미리 준비한 환율 응답
		 */
		@Override
		public ExchangeRateResponse getExchangeRate(
				String baseCurrency,
				String quoteCurrency,
				OffsetDateTime dateTime) {
			callCount++;
			this.baseCurrency = baseCurrency;
			this.quoteCurrency = quoteCurrency;
			this.dateTime = dateTime;
			return response;
		}
	}
}
