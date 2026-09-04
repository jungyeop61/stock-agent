package com.jusika.backend.toss.account;

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
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.jusika.backend.account.AccountResponse;
import com.jusika.backend.toss.TossApiProperties;
import com.jusika.backend.toss.auth.TossAccessTokenProvider;
import com.jusika.backend.toss.auth.TossAuthClient;

/**
 * 실제 토스증권 서버 대신 가짜 HTTP 서버로 계좌 목록 요청과 개인정보 보호를 검사합니다.
 */
class TossAccountClientTests {

	private static final String BASE_URL = "https://toss.example";
	private static final String ACCESS_TOKEN = "노출되면-안되는-테스트-토큰";
	private static final String ACCOUNT_NUMBER = "12345678901";

	private MockRestServiceServer server;
	private TossAccountClient accountClient;

	/**
	 * 각 테스트에서 사용할 가짜 토스증권 서버와 계좌 클라이언트를 준비합니다.
	 */
	@BeforeEach
	void 각_테스트에_필요한_가짜_계좌_서버를_준비한다() {
		RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
		server = MockRestServiceServer.bindTo(builder).build();
		RestClient restClient = builder.build();
		TossAuthClient authClient = new TossAuthClient(restClient, 테스트_인증정보를_만든다());
		Clock clock = Clock.fixed(Instant.parse("2026-09-04T00:00:00Z"), ZoneOffset.UTC);
		TossAccessTokenProvider tokenProvider = new TossAccessTokenProvider(authClient, clock);
		accountClient = new TossAccountClient(restClient, tokenProvider);
	}

	/**
	 * 인증 헤더로 계좌를 조회하고 실제 계좌번호 앞부분을 가리는지 확인합니다.
	 */
	@Test
	@DisplayName("계좌 목록을 인증 헤더와 함께 조회하고 계좌번호를 가린다")
	void 계좌_목록을_인증_헤더와_함께_조회하고_계좌번호를_가린다() {
		정상_토큰_발급_응답을_준비한다();
		server.expect(requestTo(BASE_URL + "/api/v1/accounts"))
				.andExpect(method(HttpMethod.GET))
				.andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
				.andRespond(withSuccess("""
						{
						  "result": [
						    {
						      "accountNo": "%s",
						      "accountSeq": 1,
						      "accountType": "BROKERAGE"
						    }
						  ]
						}
						""".formatted(ACCOUNT_NUMBER), MediaType.APPLICATION_JSON));

		List<AccountResponse> responses = accountClient.getAccounts();

		assertThat(responses).singleElement().satisfies(response -> {
			assertThat(response.accountSeq()).isEqualTo(1);
			assertThat(response.maskedAccountNumber()).isEqualTo("*******8901");
			assertThat(response.maskedAccountNumber()).doesNotContain(ACCOUNT_NUMBER);
			assertThat(response.accountType()).isEqualTo("BROKERAGE");
		});
		server.verify();
	}

	/**
	 * 사용 가능한 계좌가 없다는 정상 응답을 빈 목록으로 처리하는지 확인합니다.
	 */
	@Test
	@DisplayName("사용 가능한 계좌가 없으면 빈 목록을 반환한다")
	void 사용_가능한_계좌가_없으면_빈_목록을_반환한다() {
		정상_토큰_발급_응답을_준비한다();
		server.expect(requestTo(BASE_URL + "/api/v1/accounts"))
				.andRespond(withSuccess("{\"result\": []}", MediaType.APPLICATION_JSON));

		List<AccountResponse> responses = accountClient.getAccounts();

		assertThat(responses).isEmpty();
		server.verify();
	}

	/**
	 * 토스증권이 나중에 새로운 계좌 유형을 추가해도 문자열 값을 그대로 받아들이는지 확인합니다.
	 */
	@Test
	@DisplayName("알 수 없는 새 계좌 유형도 문자열로 안전하게 처리한다")
	void 알_수_없는_새_계좌_유형도_문자열로_안전하게_처리한다() {
		정상_토큰_발급_응답을_준비한다();
		server.expect(requestTo(BASE_URL + "/api/v1/accounts"))
				.andRespond(withSuccess("""
						{
						  "result": [
						    {
						      "accountNo": "%s",
						      "accountSeq": 2,
						      "accountType": "FUTURE_ACCOUNT_TYPE"
						    }
						  ]
						}
						""".formatted(ACCOUNT_NUMBER), MediaType.APPLICATION_JSON));

		List<AccountResponse> responses = accountClient.getAccounts();

		assertThat(responses).singleElement()
				.extracting(AccountResponse::accountType)
				.isEqualTo("FUTURE_ACCOUNT_TYPE");
		server.verify();
	}

	/**
	 * 토스 원본 계좌 객체를 실수로 로그에 기록해도 실제 계좌번호가 보이지 않는지 확인합니다.
	 */
	@Test
	@DisplayName("원본 계좌 객체의 문자열 표현에서 실제 계좌번호를 숨긴다")
	void 원본_계좌_객체의_문자열_표현에서_실제_계좌번호를_숨긴다() {
		TossAccountItem account = new TossAccountItem(ACCOUNT_NUMBER, 1, "BROKERAGE");
		TossAccountApiResponse response = new TossAccountApiResponse(List.of(account));

		assertThat(account.toString()).doesNotContain(ACCOUNT_NUMBER);
		assertThat(response.toString()).doesNotContain(ACCOUNT_NUMBER);
	}

	/**
	 * 토스증권 오류가 실제 계좌번호와 액세스 토큰을 포함하지 않는 예외로 바뀌는지 확인합니다.
	 */
	@Test
	@DisplayName("계좌 서버 오류에서 계좌번호와 액세스 토큰을 숨긴다")
	void 계좌_서버_오류에서_계좌번호와_액세스_토큰을_숨긴다() {
		정상_토큰_발급_응답을_준비한다();
		server.expect(requestTo(BASE_URL + "/api/v1/accounts"))
				.andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
						.contentType(MediaType.APPLICATION_JSON)
						.body("{\"accountNo\": \"" + ACCOUNT_NUMBER + "\"}"));

		assertThatThrownBy(accountClient::getAccounts)
				.isInstanceOf(TossAccountException.class)
				.hasMessage("토스증권 계좌 목록 조회에 실패했습니다. HTTP 상태: 500")
				.hasMessageNotContaining(ACCOUNT_NUMBER)
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
