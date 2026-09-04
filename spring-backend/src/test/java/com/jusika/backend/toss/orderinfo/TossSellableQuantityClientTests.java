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

import com.jusika.backend.sellablequantity.SellableQuantityResponse;
import com.jusika.backend.toss.TossApiProperties;
import com.jusika.backend.toss.auth.TossAccessTokenProvider;
import com.jusika.backend.toss.auth.TossAuthClient;

/**
 * 실제 토스증권 서버 대신 가짜 HTTP 서버로 국내와 미국 주식의 매도 가능 수량 조회를 검사합니다.
 */
class TossSellableQuantityClientTests {

	private static final String BASE_URL = "https://toss.example";
	private static final String ACCESS_TOKEN = "노출되면-안되는-테스트-토큰";
	private static final long ACCOUNT_SEQ = 1L;

	private MockRestServiceServer server;
	private TossSellableQuantityClient sellableQuantityClient;

	/**
	 * 각 테스트에서 사용할 가짜 토스증권 서버와 매도 가능 수량 클라이언트를 준비합니다.
	 */
	@BeforeEach
	void 각_테스트에_필요한_가짜_주문정보_서버를_준비한다() {
		RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
		server = MockRestServiceServer.bindTo(builder).build();
		RestClient restClient = builder.build();
		TossAuthClient authClient = new TossAuthClient(restClient, 테스트_인증정보를_만든다());
		Clock clock = Clock.fixed(Instant.parse("2026-09-04T00:00:00Z"), ZoneOffset.UTC);
		TossAccessTokenProvider tokenProvider = new TossAccessTokenProvider(authClient, clock);
		sellableQuantityClient = new TossSellableQuantityClient(restClient, tokenProvider);
	}

	/**
	 * 국내 주식 요청에 계좌와 인증 헤더를 전달하고 정수 수량을 숫자로 변환하는지 확인합니다.
	 */
	@Test
	@DisplayName("국내 주식의 매도 가능 수량을 계좌 식별값으로 조회한다")
	void 국내_주식의_매도_가능_수량을_계좌_식별값으로_조회한다() {
		정상_토큰_발급_응답을_준비한다();
		매도_가능_수량_응답을_준비한다("005930", "100");

		SellableQuantityResponse response = sellableQuantityClient.getSellableQuantity(ACCOUNT_SEQ, "005930");

		assertThat(response.accountSeq()).isEqualTo(ACCOUNT_SEQ);
		assertThat(response.symbol()).isEqualTo("005930");
		assertThat(response.sellableQuantity()).isEqualByComparingTo("100");
		server.verify();
	}

	/**
	 * 미국 주식의 소문자 티커를 대문자로 바꾸고 소수 수량을 그대로 보존하는지 확인합니다.
	 */
	@Test
	@DisplayName("미국 주식의 소수 단위 매도 가능 수량을 정확하게 처리한다")
	void 미국_주식의_소수_단위_매도_가능_수량을_정확하게_처리한다() {
		정상_토큰_발급_응답을_준비한다();
		매도_가능_수량_응답을_준비한다("AAPL", "5.5");

		SellableQuantityResponse response = sellableQuantityClient.getSellableQuantity(ACCOUNT_SEQ, "aapl");

		assertThat(response.symbol()).isEqualTo("AAPL");
		assertThat(response.sellableQuantity()).isEqualByComparingTo("5.5");
		server.verify();
	}

	/**
	 * 허용되지 않은 문자가 포함된 종목 코드를 외부 요청 전에 차단하는지 확인합니다.
	 */
	@Test
	@DisplayName("잘못된 종목 코드는 토스증권 호출 전에 차단한다")
	void 잘못된_종목_코드는_토스증권_호출_전에_차단한다() {
		assertThatThrownBy(() -> sellableQuantityClient.getSellableQuantity(ACCOUNT_SEQ, "005930,000660"))
				.isInstanceOf(TossOrderInfoException.class)
				.hasMessage("종목 코드 형식이 올바르지 않습니다.");
		server.verify();
	}

	/**
	 * 0이나 음수인 계좌 식별값을 외부 요청 전에 차단하는지 확인합니다.
	 */
	@Test
	@DisplayName("잘못된 계좌 식별값은 토스증권 호출 전에 차단한다")
	void 잘못된_계좌_식별값은_토스증권_호출_전에_차단한다() {
		assertThatThrownBy(() -> sellableQuantityClient.getSellableQuantity(0, "005930"))
				.isInstanceOf(TossOrderInfoException.class)
				.hasMessage("계좌 식별값은 1 이상이어야 합니다.");
		server.verify();
	}

	/**
	 * 숫자가 아닌 수량을 주문 검증에 사용하지 않고 안전한 응답 오류로 처리하는지 확인합니다.
	 */
	@Test
	@DisplayName("숫자가 아닌 매도 가능 수량은 안전하게 거절한다")
	void 숫자가_아닌_매도_가능_수량은_안전하게_거절한다() {
		정상_토큰_발급_응답을_준비한다();
		매도_가능_수량_응답을_준비한다("005930", "백주");

		assertThatThrownBy(() -> sellableQuantityClient.getSellableQuantity(ACCOUNT_SEQ, "005930"))
				.isInstanceOf(TossOrderInfoException.class)
				.hasMessage("토스증권 매도 가능 수량의 숫자 형식이 올바르지 않습니다.")
				.hasMessageNotContaining("백주");
		server.verify();
	}

	/**
	 * 금융 처리에 사용할 수 없는 음수 수량을 안전하게 거절하는지 확인합니다.
	 */
	@Test
	@DisplayName("음수인 매도 가능 수량은 안전하게 거절한다")
	void 음수인_매도_가능_수량은_안전하게_거절한다() {
		정상_토큰_발급_응답을_준비한다();
		매도_가능_수량_응답을_준비한다("005930", "-1");

		assertThatThrownBy(() -> sellableQuantityClient.getSellableQuantity(ACCOUNT_SEQ, "005930"))
				.isInstanceOf(TossOrderInfoException.class)
				.hasMessage("토스증권 매도 가능 수량은 음수일 수 없습니다.");
		server.verify();
	}

	/**
	 * 토스 원본 응답 객체를 실수로 로그에 기록해도 실제 매도 가능 수량이 보이지 않는지 확인합니다.
	 */
	@Test
	@DisplayName("원본 응답 객체의 문자열 표현에서 매도 가능 수량을 숨긴다")
	void 원본_응답_객체의_문자열_표현에서_매도_가능_수량을_숨긴다() {
		TossSellableQuantityApiResponse.TossSellableQuantityResult result =
				new TossSellableQuantityApiResponse.TossSellableQuantityResult("987654321");
		TossSellableQuantityApiResponse response = new TossSellableQuantityApiResponse(result);

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
		server.expect(requestTo(BASE_URL + "/api/v1/sellable-quantity?symbol=005930"))
				.andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
						.contentType(MediaType.APPLICATION_JSON)
						.body("{\"privateQuantity\": \"987654321\"}"));

		assertThatThrownBy(() -> sellableQuantityClient.getSellableQuantity(ACCOUNT_SEQ, "005930"))
				.isInstanceOf(TossOrderInfoException.class)
				.hasMessage("토스증권 매도 가능 수량 조회에 실패했습니다. HTTP 상태: 500")
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
	 * 가짜 주문 정보 서버가 지정한 종목의 매도 가능 수량을 반환하도록 준비합니다.
	 *
	 * @param symbol 요청에서 검사할 종목 코드
	 * @param sellableQuantity 반환할 문자열 형태의 매도 가능 수량
	 */
	private void 매도_가능_수량_응답을_준비한다(String symbol, String sellableQuantity) {
		server.expect(requestTo(BASE_URL + "/api/v1/sellable-quantity?symbol=" + symbol))
				.andExpect(method(HttpMethod.GET))
				.andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
				.andExpect(header("X-Tossinvest-Account", Long.toString(ACCOUNT_SEQ)))
				.andRespond(withSuccess("""
						{
						  "result": {
						    "sellableQuantity": "%s"
						  }
						}
						""".formatted(sellableQuantity), MediaType.APPLICATION_JSON));
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
