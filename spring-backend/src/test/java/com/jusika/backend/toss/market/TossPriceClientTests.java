package com.jusika.backend.toss.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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

import com.jusika.backend.stock.StockPriceResponse;
import com.jusika.backend.toss.TossApiProperties;
import com.jusika.backend.toss.auth.TossAccessTokenProvider;
import com.jusika.backend.toss.auth.TossAuthClient;

/**
 * 실제 토스증권 서버 대신 가짜 HTTP 서버로 현재가 요청과 응답 변환을 검사합니다.
 */
class TossPriceClientTests {

	private static final String BASE_URL = "https://toss.example";
	private static final String ACCESS_TOKEN = "노출되면-안되는-테스트-토큰";

	private MockRestServiceServer server;
	private TossPriceClient priceClient;

	/**
	 * 각 테스트에서 사용할 가짜 토스증권 서버와 현재가 클라이언트를 준비합니다.
	 */
	@BeforeEach
	void 각_테스트에_필요한_가짜_시세_서버를_준비한다() {
		RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
		server = MockRestServiceServer.bindTo(builder).build();
		RestClient restClient = builder.build();
		TossAuthClient authClient = new TossAuthClient(restClient, 테스트_인증정보를_만든다());
		Clock clock = Clock.fixed(Instant.parse("2026-09-04T00:00:00Z"), ZoneOffset.UTC);
		TossAccessTokenProvider tokenProvider = new TossAccessTokenProvider(authClient, clock);
		priceClient = new TossPriceClient(restClient, tokenProvider);
	}

	/**
	 * 액세스 토큰을 Authorization 헤더에 담아 요청하고 현재가를 숫자로 변환하는지 확인합니다.
	 */
	@Test
	@DisplayName("삼성전자 현재가를 인증 헤더와 함께 조회한다")
	void 삼성전자_현재가를_인증_헤더와_함께_조회한다() {
		정상_토큰_발급_응답을_준비한다();
		server.expect(requestTo(BASE_URL + "/api/v1/prices?symbols=005930"))
				.andExpect(method(HttpMethod.GET))
				.andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
				.andRespond(withSuccess("""
						{
						  "result": [
						    {
						      "symbol": "005930",
						      "timestamp": "2026-09-04T09:30:00.123+09:00",
						      "lastPrice": "72000",
						      "currency": "KRW"
						    }
						  ]
						}
						""", MediaType.APPLICATION_JSON));

		StockPriceResponse response = priceClient.getCurrentPrice("005930");

		assertThat(response.symbol()).isEqualTo("005930");
		assertThat(response.price()).isEqualByComparingTo("72000");
		assertThat(response.currency()).isEqualTo("KRW");
		assertThat(response.timestamp()).hasToString("2026-09-04T09:30:00.123+09:00");
		server.verify();
	}

	/**
	 * 허용되지 않은 문자가 포함된 종목 코드를 외부 요청 전에 차단하는지 확인합니다.
	 */
	@Test
	@DisplayName("잘못된 종목 코드는 토스증권 호출 전에 차단한다")
	void 잘못된_종목_코드는_토스증권_호출_전에_차단한다() {
		assertThatThrownBy(() -> priceClient.getCurrentPrice("005930?secret=true"))
				.isInstanceOf(TossMarketDataException.class)
				.hasMessage("종목 코드 형식이 올바르지 않습니다.");
		server.verify();
	}

	/**
	 * 토스증권 오류가 액세스 토큰을 포함하지 않는 안전한 내부 예외로 바뀌는지 확인합니다.
	 */
	@Test
	@DisplayName("시세 서버 오류에서 액세스 토큰을 숨긴다")
	void 시세_서버_오류에서_액세스_토큰을_숨긴다() {
		정상_토큰_발급_응답을_준비한다();
		server.expect(requestTo(BASE_URL + "/api/v1/prices?symbols=005930"))
				.andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

		assertThatThrownBy(() -> priceClient.getCurrentPrice("005930"))
				.isInstanceOf(TossMarketDataException.class)
				.hasMessage("토스증권 현재가 조회에 실패했습니다. HTTP 상태: 500")
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
