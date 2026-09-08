package com.jusika.backend.toss.marketinfo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.jusika.backend.exchangerate.ExchangeRateChangeType;
import com.jusika.backend.exchangerate.ExchangeRateRequestException;
import com.jusika.backend.exchangerate.ExchangeRateResponse;
import com.jusika.backend.toss.TossApiProperties;
import com.jusika.backend.toss.auth.TossAccessTokenProvider;
import com.jusika.backend.toss.auth.TossAuthClient;

/**
 * 실제 토스증권 서버 대신 가짜 HTTP 서버로 환율 요청과 응답 검증을 검사합니다.
 */
class TossExchangeRateClientTests {

	private static final String BASE_URL = "https://toss.example";
	private static final String ACCESS_TOKEN = "노출되면-안되는-테스트-토큰";

	private MockRestServiceServer server;
	private TossExchangeRateClient exchangeRateClient;

	/**
	 * 각 테스트에서 사용할 가짜 토스증권 서버와 환율 클라이언트를 준비합니다.
	 */
	@BeforeEach
	void 각_테스트에_필요한_가짜_환율_서버를_준비한다() {
		RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
		server = MockRestServiceServer.bindTo(builder).build();
		RestClient restClient = builder.build();
		TossAuthClient authClient = new TossAuthClient(restClient, 테스트_인증정보를_만든다());
		Clock clock = Clock.fixed(Instant.parse("2026-09-08T00:00:00Z"), ZoneOffset.UTC);
		TossAccessTokenProvider tokenProvider = new TossAccessTokenProvider(authClient, clock);
		exchangeRateClient = new TossExchangeRateClient(restClient, tokenProvider);
	}

	/**
	 * 현재 USD 기준 원화 환율을 인증 헤더와 통화 쿼리로 조회하는지 검사합니다.
	 */
	@Test
	@DisplayName("현재 달러 원화 환율을 인증 헤더와 함께 조회한다")
	void 현재_달러_원화_환율을_인증_헤더와_함께_조회한다() {
		정상_토큰_발급_응답을_준비한다();
		server.expect(requestTo(
				BASE_URL + "/api/v1/exchange-rate?baseCurrency=USD&quoteCurrency=KRW"))
				.andExpect(method(HttpMethod.GET))
				.andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
				.andRespond(withSuccess(정상_환율_응답(), MediaType.APPLICATION_JSON));

		ExchangeRateResponse response = exchangeRateClient.getExchangeRate("usd", "krw", null);

		assertThat(response.baseCurrency()).isEqualTo("USD");
		assertThat(response.quoteCurrency()).isEqualTo("KRW");
		assertThat(response.rate()).isEqualByComparingTo("1380.5");
		assertThat(response.midRate()).isEqualByComparingTo("1375");
		assertThat(response.basisPoint()).isEqualByComparingTo("40");
		assertThat(response.rateChangeType()).isEqualTo(ExchangeRateChangeType.UP);
		assertThat(response.validFrom())
				.isEqualTo(OffsetDateTime.parse("2026-09-08T09:30:00+09:00"));
		assertThat(response.validUntil())
				.isEqualTo(OffsetDateTime.parse("2026-09-08T09:31:00+09:00"));
		server.verify();
	}

	/**
	 * 특정 시점 조회에서는 ISO 8601 시각을 dateTime 쿼리에 포함하는지 검사합니다.
	 */
	@Test
	@DisplayName("지정한 시점의 환율을 조회한다")
	void 지정한_시점의_환율을_조회한다() {
		정상_토큰_발급_응답을_준비한다();
		OffsetDateTime requestedAt = OffsetDateTime.parse("2026-09-08T09:30:00+09:00");
		server.expect(requestTo(startsWith(BASE_URL + "/api/v1/exchange-rate")))
				.andExpect(queryParam("baseCurrency", "USD"))
				.andExpect(queryParam("quoteCurrency", "KRW"))
				.andExpect(queryParam("dateTime", requestedAt.toString()))
				.andRespond(withSuccess(정상_환율_응답(), MediaType.APPLICATION_JSON));

		ExchangeRateResponse response = exchangeRateClient.getExchangeRate(
				"USD", "KRW", requestedAt);

		assertThat(response.rate()).isEqualByComparingTo("1380.5");
		server.verify();
	}

	/**
	 * 원화와 달러 이외 통화 또는 같은 통화 조합을 외부 호출 전에 차단하는지 검사합니다.
	 */
	@Test
	@DisplayName("지원하지 않는 환율 통화 조합을 외부 호출 전에 거절한다")
	void 지원하지_않는_환율_통화_조합을_외부_호출_전에_거절한다() {
		assertThatThrownBy(() -> exchangeRateClient.getExchangeRate("EUR", "KRW", null))
				.isInstanceOf(ExchangeRateRequestException.class)
				.hasMessage("통화 코드는 KRW 또는 USD여야 합니다.");
		assertThatThrownBy(() -> exchangeRateClient.getExchangeRate("USD", "usd", null))
				.isInstanceOf(ExchangeRateRequestException.class)
				.hasMessage("기준 통화와 상대 통화는 서로 달라야 합니다.");
		server.verify();
	}

	/**
	 * 토스증권이 요청과 다른 통화를 반환하면 결과를 사용하지 않는지 검사합니다.
	 */
	@Test
	@DisplayName("요청과 다른 통화의 환율 응답을 거절한다")
	void 요청과_다른_통화의_환율_응답을_거절한다() {
		정상_토큰_발급_응답을_준비한다();
		server.expect(requestTo(
				BASE_URL + "/api/v1/exchange-rate?baseCurrency=USD&quoteCurrency=KRW"))
				.andRespond(withSuccess(정상_환율_응답().replace(
						"\"baseCurrency\": \"USD\"", "\"baseCurrency\": \"KRW\""),
						MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> exchangeRateClient.getExchangeRate("USD", "KRW", null))
				.isInstanceOf(TossMarketInfoException.class)
				.hasMessage("토스증권이 요청과 다른 통화의 환율을 반환했습니다.");
		server.verify();
	}

	/**
	 * 양수가 아닌 환율이나 뒤집힌 유효시간을 올바르지 않은 응답으로 거절하는지 검사합니다.
	 */
	@Test
	@DisplayName("범위가 잘못된 환율 응답을 거절한다")
	void 범위가_잘못된_환율_응답을_거절한다() {
		정상_토큰_발급_응답을_준비한다();
		server.expect(requestTo(
				BASE_URL + "/api/v1/exchange-rate?baseCurrency=USD&quoteCurrency=KRW"))
				.andRespond(withSuccess(정상_환율_응답().replace(
						"\"rate\": \"1380.5\"", "\"rate\": \"0\""),
						MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> exchangeRateClient.getExchangeRate("USD", "KRW", null))
				.isInstanceOf(TossMarketInfoException.class)
				.hasMessage("토스증권 환율 응답의 값 범위가 올바르지 않습니다.");
		server.verify();
	}

	/**
	 * 잘못된 숫자·시각·등락 문자열을 안전한 응답 형식 오류로 바꾸는지 검사합니다.
	 */
	@Test
	@DisplayName("잘못된 환율 응답 형식을 거절한다")
	void 잘못된_환율_응답_형식을_거절한다() {
		정상_토큰_발급_응답을_준비한다();
		server.expect(requestTo(
				BASE_URL + "/api/v1/exchange-rate?baseCurrency=USD&quoteCurrency=KRW"))
				.andRespond(withSuccess(정상_환율_응답().replace(
						"\"rateChangeType\": \"UP\"", "\"rateChangeType\": \"UNKNOWN\""),
						MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> exchangeRateClient.getExchangeRate("USD", "KRW", null))
				.isInstanceOf(TossMarketInfoException.class)
				.hasMessage("토스증권 환율의 숫자, 시각 또는 등락 형식이 올바르지 않습니다.");
		server.verify();
	}

	/**
	 * 토스증권 오류를 토큰이 포함되지 않은 안전한 내부 예외로 바꾸는지 검사합니다.
	 */
	@Test
	@DisplayName("환율 서버 오류에서 액세스 토큰을 숨긴다")
	void 환율_서버_오류에서_액세스_토큰을_숨긴다() {
		정상_토큰_발급_응답을_준비한다();
		server.expect(requestTo(
				BASE_URL + "/api/v1/exchange-rate?baseCurrency=USD&quoteCurrency=KRW"))
				.andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

		assertThatThrownBy(() -> exchangeRateClient.getExchangeRate("USD", "KRW", null))
				.isInstanceOf(TossMarketInfoException.class)
				.hasMessage("토스증권 환율 조회에 실패했습니다. HTTP 상태: 500")
				.hasMessageNotContaining(ACCESS_TOKEN);
		server.verify();
	}

	/**
	 * 가짜 인증 서버가 충분한 유효시간의 액세스 토큰을 반환하도록 준비합니다.
	 */
	private void 정상_토큰_발급_응답을_준비한다() {
		server.expect(requestTo(BASE_URL + "/oauth2/token"))
				.andRespond(withSuccess("""
						{
						  "access_token": "%s",
						  "token_type": "Bearer",
						  "expires_in": 86400
						}
						""".formatted(ACCESS_TOKEN), MediaType.APPLICATION_JSON));
	}

	/**
	 * 가짜 토스증권 서버가 반환할 정상 환율 JSON을 만듭니다.
	 *
	 * @return 필수 필드가 모두 포함된 환율 응답 본문
	 */
	private String 정상_환율_응답() {
		return """
				{
				  "result": {
				    "baseCurrency": "USD",
				    "quoteCurrency": "KRW",
				    "rate": "1380.5",
				    "midRate": "1375",
				    "basisPoint": "40",
				    "rateChangeType": "UP",
				    "validFrom": "2026-09-08T09:30:00+09:00",
				    "validUntil": "2026-09-08T09:31:00+09:00"
				  }
				}
				""";
	}

	/**
	 * 가짜 토스증권 서버와 연결할 테스트용 인증 설정을 만듭니다.
	 *
	 * @return 가짜 서버 주소와 가짜 인증정보가 들어 있는 설정
	 */
	private TossApiProperties 테스트_인증정보를_만든다() {
		return new TossApiProperties(
				URI.create(BASE_URL),
				"테스트-클라이언트-아이디",
				"테스트-클라이언트-비밀키",
				Duration.ofSeconds(3),
				Duration.ofSeconds(5));
	}
}
