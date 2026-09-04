package com.jusika.backend.toss.orderinfo;

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

import com.jusika.backend.buyingpower.BuyingPowerResponse;
import com.jusika.backend.toss.TossApiProperties;
import com.jusika.backend.toss.auth.TossAccessTokenProvider;
import com.jusika.backend.toss.auth.TossAuthClient;

/**
 * 실제 토스증권 서버 대신 가짜 HTTP 서버로 원화와 달러 매수 가능 금액 조회를 검사합니다.
 */
class TossBuyingPowerClientTests {

	private static final String BASE_URL = "https://toss.example";
	private static final String ACCESS_TOKEN = "노출되면-안되는-테스트-토큰";
	private static final long ACCOUNT_SEQ = 1L;

	private MockRestServiceServer server;
	private TossBuyingPowerClient buyingPowerClient;

	/**
	 * 각 테스트에서 사용할 가짜 토스증권 서버와 매수 가능 금액 클라이언트를 준비합니다.
	 */
	@BeforeEach
	void 각_테스트에_필요한_가짜_주문정보_서버를_준비한다() {
		RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
		server = MockRestServiceServer.bindTo(builder).build();
		RestClient restClient = builder.build();
		TossAuthClient authClient = new TossAuthClient(restClient, 테스트_인증정보를_만든다());
		Clock clock = Clock.fixed(Instant.parse("2026-09-04T00:00:00Z"), ZoneOffset.UTC);
		TossAccessTokenProvider tokenProvider = new TossAccessTokenProvider(authClient, clock);
		buyingPowerClient = new TossBuyingPowerClient(restClient, tokenProvider);
	}

	/**
	 * 원화 요청에 계좌 식별 헤더와 인증 헤더를 전달하고 금액을 숫자로 변환하는지 확인합니다.
	 */
	@Test
	@DisplayName("원화 매수 가능 금액을 계좌 식별값으로 조회한다")
	void 원화_매수_가능_금액을_계좌_식별값으로_조회한다() {
		정상_토큰_발급_응답을_준비한다();
		매수_가능_금액_응답을_준비한다("KRW", "5000000");

		BuyingPowerResponse response = buyingPowerClient.getBuyingPower(ACCOUNT_SEQ, "KRW");

		assertThat(response.accountSeq()).isEqualTo(ACCOUNT_SEQ);
		assertThat(response.currency()).isEqualTo("KRW");
		assertThat(response.cashBuyingPower()).isEqualByComparingTo("5000000");
		server.verify();
	}

	/**
	 * 사용자가 소문자로 입력한 달러 통화 코드를 대문자로 바꾸고 소수 금액을 보존하는지 확인합니다.
	 */
	@Test
	@DisplayName("소문자로 입력한 달러 통화와 소수 금액을 정확하게 처리한다")
	void 소문자로_입력한_달러_통화와_소수_금액을_정확하게_처리한다() {
		정상_토큰_발급_응답을_준비한다();
		매수_가능_금액_응답을_준비한다("USD", "3500.5");

		BuyingPowerResponse response = buyingPowerClient.getBuyingPower(ACCOUNT_SEQ, "usd");

		assertThat(response.currency()).isEqualTo("USD");
		assertThat(response.cashBuyingPower()).isEqualByComparingTo("3500.5");
		server.verify();
	}

	/**
	 * 토스증권이 지원하지 않는 통화 코드를 외부 요청 전에 차단하는지 확인합니다.
	 */
	@Test
	@DisplayName("원화와 달러가 아닌 통화는 토스증권 호출 전에 차단한다")
	void 원화와_달러가_아닌_통화는_토스증권_호출_전에_차단한다() {
		assertThatThrownBy(() -> buyingPowerClient.getBuyingPower(ACCOUNT_SEQ, "JPY"))
				.isInstanceOf(TossOrderInfoException.class)
				.hasMessage("통화 코드는 KRW 또는 USD여야 합니다.");
		server.verify();
	}

	/**
	 * 0이나 음수인 계좌 식별값을 외부 요청 전에 차단하는지 확인합니다.
	 */
	@Test
	@DisplayName("잘못된 계좌 식별값은 토스증권 호출 전에 차단한다")
	void 잘못된_계좌_식별값은_토스증권_호출_전에_차단한다() {
		assertThatThrownBy(() -> buyingPowerClient.getBuyingPower(0, "KRW"))
				.isInstanceOf(TossOrderInfoException.class)
				.hasMessage("계좌 식별값은 1 이상이어야 합니다.");
		server.verify();
	}

	/**
	 * 요청한 통화와 토스증권이 반환한 통화가 다르면 잘못된 금액 사용을 막는지 확인합니다.
	 */
	@Test
	@DisplayName("요청과 다른 통화의 응답은 안전하게 거절한다")
	void 요청과_다른_통화의_응답은_안전하게_거절한다() {
		정상_토큰_발급_응답을_준비한다();
		server.expect(requestTo(BASE_URL + "/api/v1/buying-power?currency=KRW"))
				.andRespond(withSuccess("""
						{"result": {"currency": "USD", "cashBuyingPower": "3500.5"}}
						""", MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> buyingPowerClient.getBuyingPower(ACCOUNT_SEQ, "KRW"))
				.isInstanceOf(TossOrderInfoException.class)
				.hasMessage("토스증권이 요청과 다른 통화의 금액을 반환했습니다.");
		server.verify();
	}

	/**
	 * 숫자가 아닌 금액을 계산에 사용하지 않고 안전한 응답 오류로 처리하는지 확인합니다.
	 */
	@Test
	@DisplayName("숫자가 아닌 매수 가능 금액은 안전하게 거절한다")
	void 숫자가_아닌_매수_가능_금액은_안전하게_거절한다() {
		정상_토큰_발급_응답을_준비한다();
		매수_가능_금액_응답을_준비한다("KRW", "오백만원");

		assertThatThrownBy(() -> buyingPowerClient.getBuyingPower(ACCOUNT_SEQ, "KRW"))
				.isInstanceOf(TossOrderInfoException.class)
				.hasMessage("토스증권 매수 가능 금액의 숫자 형식이 올바르지 않습니다.")
				.hasMessageNotContaining("오백만원");
		server.verify();
	}

	/**
	 * 토스 원본 응답 객체를 실수로 로그에 기록해도 실제 매수 가능 금액이 보이지 않는지 확인합니다.
	 */
	@Test
	@DisplayName("원본 응답 객체의 문자열 표현에서 매수 가능 금액을 숨긴다")
	void 원본_응답_객체의_문자열_표현에서_매수_가능_금액을_숨긴다() {
		TossBuyingPowerApiResponse.TossBuyingPowerResult result =
				new TossBuyingPowerApiResponse.TossBuyingPowerResult("KRW", "987654321");
		TossBuyingPowerApiResponse response = new TossBuyingPowerApiResponse(result);

		assertThat(result.toString()).doesNotContain("987654321");
		assertThat(response.toString()).doesNotContain("987654321");
	}

	/**
	 * 토스증권 오류가 액세스 토큰과 금융정보를 포함하지 않는 안전한 예외로 바뀌는지 확인합니다.
	 */
	@Test
	@DisplayName("주문 정보 서버 오류에서 액세스 토큰과 금융정보를 숨긴다")
	void 주문_정보_서버_오류에서_액세스_토큰과_금융정보를_숨긴다() {
		정상_토큰_발급_응답을_준비한다();
		server.expect(requestTo(BASE_URL + "/api/v1/buying-power?currency=KRW"))
				.andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
						.contentType(MediaType.APPLICATION_JSON)
						.body("{\"privateAmount\": \"987654321\"}"));

		assertThatThrownBy(() -> buyingPowerClient.getBuyingPower(ACCOUNT_SEQ, "KRW"))
				.isInstanceOf(TossOrderInfoException.class)
				.hasMessage("토스증권 매수 가능 금액 조회에 실패했습니다. HTTP 상태: 500")
				.hasMessageNotContaining("987654321")
				.hasMessageNotContaining(ACCESS_TOKEN)
				.hasNoCause();
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
	 * 가짜 주문 정보 서버가 지정한 통화와 매수 가능 금액을 반환하도록 준비합니다.
	 *
	 * @param currency 반환할 통화 코드
	 * @param cashBuyingPower 반환할 문자열 형태의 매수 가능 금액
	 */
	private void 매수_가능_금액_응답을_준비한다(String currency, String cashBuyingPower) {
		server.expect(requestTo(BASE_URL + "/api/v1/buying-power?currency=" + currency))
				.andExpect(method(HttpMethod.GET))
				.andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
				.andExpect(header("X-Tossinvest-Account", Long.toString(ACCOUNT_SEQ)))
				.andRespond(withSuccess("""
						{
						  "result": {
						    "currency": "%s",
						    "cashBuyingPower": "%s"
						  }
						}
						""".formatted(currency, cashBuyingPower), MediaType.APPLICATION_JSON));
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
