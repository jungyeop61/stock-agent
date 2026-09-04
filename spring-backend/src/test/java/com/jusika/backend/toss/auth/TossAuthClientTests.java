package com.jusika.backend.toss.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.URI;
import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import com.jusika.backend.toss.TossApiProperties;

/**
 * 실제 토스증권 서버 대신 가짜 HTTP 서버를 사용해 인증 요청을 빠르고 안전하게 검사합니다.
 */
class TossAuthClientTests {

	private static final String BASE_URL = "https://toss.example";
	private static final String CLIENT_ID = "테스트-클라이언트-아이디";
	private static final String CLIENT_SECRET = "테스트-클라이언트-비밀키";

	private MockRestServiceServer server;
	private TossAuthClient authClient;

	/**
	 * 각 테스트가 서로 영향을 주지 않도록 새로운 가짜 서버와 인증 클라이언트를 준비합니다.
	 */
	@BeforeEach
	void 각_테스트에_필요한_가짜_서버를_준비한다() {
		RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
		server = MockRestServiceServer.bindTo(builder).build();
		authClient = new TossAuthClient(builder.build(), 정상_인증정보를_만든다());
	}

	/**
	 * 성공 응답의 토큰 종류와 만료시간이 자바 객체로 정확하게 변환되는지 확인합니다.
	 */
	@Test
	@DisplayName("토큰 발급 요청이 성공하면 토큰 정보를 반환한다")
	void 토큰_발급_요청이_성공하면_토큰_정보를_반환한다() {
		MultiValueMap<String, String> expectedForm = new LinkedMultiValueMap<>();
		expectedForm.add("grant_type", "client_credentials");
		expectedForm.add("client_id", CLIENT_ID);
		expectedForm.add("client_secret", CLIENT_SECRET);

		server.expect(requestTo(BASE_URL + "/oauth2/token"))
				.andExpect(method(HttpMethod.POST))
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
				.andExpect(content().formData(expectedForm))
				.andRespond(withSuccess("""
						{
						  "access_token": "테스트-액세스-토큰",
						  "token_type": "Bearer",
						  "expires_in": 86400
						}
						""", MediaType.APPLICATION_JSON));

		TossTokenResponse response = authClient.issueAccessToken();

		assertThat(response.accessToken()).isEqualTo("테스트-액세스-토큰");
		assertThat(response.tokenType()).isEqualTo("Bearer");
		assertThat(response.expiresIn()).isEqualTo(86400);
		server.verify();
	}

	/**
	 * 잘못된 인증정보로 받은 오류가 비밀값 없는 안전한 내부 예외로 바뀌는지 확인합니다.
	 */
	@Test
	@DisplayName("인증 서버가 요청을 거절하면 안전한 인증 예외를 발생시킨다")
	void 인증_서버가_요청을_거절하면_안전한_인증_예외를_발생시킨다() {
		server.expect(requestTo(BASE_URL + "/oauth2/token"))
				.andRespond(withStatus(HttpStatus.UNAUTHORIZED)
						.contentType(MediaType.APPLICATION_JSON)
						.body("""
								{
								  "error": "invalid_client",
								  "error_description": "Client authentication failed."
								}
								"""));

		assertThatThrownBy(authClient::issueAccessToken)
				.isInstanceOf(TossAuthenticationException.class)
				.hasMessage("토스증권 인증에 실패했습니다. HTTP 상태: 401")
				.hasMessageNotContaining(CLIENT_ID)
				.hasMessageNotContaining(CLIENT_SECRET);
		server.verify();
	}

	/**
	 * 비밀 환경변수가 빠졌을 때 외부 서버를 호출하기 전에 즉시 차단되는지 확인합니다.
	 */
	@Test
	@DisplayName("필수 인증정보가 비어 있으면 외부 요청 전에 차단한다")
	void 필수_인증정보가_비어_있으면_외부_요청_전에_차단한다() {
		TossApiProperties emptyProperties = new TossApiProperties(
				URI.create(BASE_URL),
				"",
				"",
				Duration.ofSeconds(3),
				Duration.ofSeconds(5));
		TossAuthClient emptyAuthClient = new TossAuthClient(RestClient.create(BASE_URL), emptyProperties);

		assertThatThrownBy(emptyAuthClient::issueAccessToken)
				.isInstanceOf(TossAuthenticationException.class)
				.hasMessageContaining("TOSSINVEST_CLIENT_ID")
				.hasMessageContaining("TOSSINVEST_CLIENT_SECRET");
	}

	/**
	 * 개발자가 객체 전체를 로그로 출력해도 클라이언트 비밀키와 액세스 토큰이 보이지 않는지 확인합니다.
	 */
	@Test
	@DisplayName("설정과 토큰의 문자열 표현에서 비밀정보를 숨긴다")
	void 설정과_토큰의_문자열_표현에서_비밀정보를_숨긴다() {
		TossApiProperties properties = 정상_인증정보를_만든다();
		TossTokenResponse response = new TossTokenResponse("노출되면-안되는-토큰", "Bearer", 86400);

		assertThat(properties.toString())
				.doesNotContain(CLIENT_ID)
				.doesNotContain(CLIENT_SECRET);
		assertThat(response.toString()).doesNotContain("노출되면-안되는-토큰");
	}

	/**
	 * 여러 테스트에서 사용할 정상적인 가짜 인증 설정을 만듭니다.
	 *
	 * @return 가짜 서버와 테스트용 인증정보가 들어 있는 설정
	 */
	private TossApiProperties 정상_인증정보를_만든다() {
		return new TossApiProperties(
				URI.create(BASE_URL),
				CLIENT_ID,
				CLIENT_SECRET,
				Duration.ofSeconds(3),
				Duration.ofSeconds(5));
	}
}
