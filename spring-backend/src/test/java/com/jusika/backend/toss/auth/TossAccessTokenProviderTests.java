package com.jusika.backend.toss.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.jusika.backend.toss.TossApiProperties;

/**
 * 액세스 토큰을 불필요하게 다시 발급하지 않고 안전하게 재사용하는지 검사합니다.
 */
class TossAccessTokenProviderTests {

	private static final String BASE_URL = "https://toss.example";

	private MockRestServiceServer server;
	private TossAccessTokenProvider tokenProvider;

	/**
	 * 각 테스트가 사용할 가짜 인증 서버와 고정된 시각의 토큰 공급자를 준비합니다.
	 */
	@BeforeEach
	void 각_테스트에_필요한_토큰_공급자를_준비한다() {
		RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
		server = MockRestServiceServer.bindTo(builder).build();
		TossAuthClient authClient = new TossAuthClient(builder.build(), 테스트_인증정보를_만든다());
		Clock clock = Clock.fixed(Instant.parse("2026-09-04T00:00:00Z"), ZoneOffset.UTC);
		tokenProvider = new TossAccessTokenProvider(authClient, clock);
	}

	/**
	 * 유효시간이 충분히 남은 토큰을 여러 번 요청해도 실제 발급 요청은 한 번만 보내는지 확인합니다.
	 */
	@Test
	@DisplayName("유효한 액세스 토큰은 새로 발급하지 않고 재사용한다")
	void 유효한_액세스_토큰은_새로_발급하지_않고_재사용한다() {
		토큰_발급_응답을_준비한다("재사용할-토큰", 86400);

		String firstToken = tokenProvider.getAccessToken();
		String secondToken = tokenProvider.getAccessToken();

		assertThat(firstToken).isEqualTo("재사용할-토큰");
		assertThat(secondToken).isEqualTo("재사용할-토큰");
		server.verify();
	}

	/**
	 * 안전 여유시간보다 수명이 짧은 토큰은 다음 요청에서 새 토큰으로 교체하는지 확인합니다.
	 */
	@Test
	@DisplayName("곧 만료될 액세스 토큰은 다음 요청에서 다시 발급한다")
	void 곧_만료될_액세스_토큰은_다음_요청에서_다시_발급한다() {
		토큰_발급_응답을_준비한다("곧-만료될-토큰", 30);
		토큰_발급_응답을_준비한다("새로운-토큰", 86400);

		String firstToken = tokenProvider.getAccessToken();
		String secondToken = tokenProvider.getAccessToken();

		assertThat(firstToken).isEqualTo("곧-만료될-토큰");
		assertThat(secondToken).isEqualTo("새로운-토큰");
		server.verify();
	}

	/**
	 * 가짜 인증 서버가 지정된 값과 만료시간을 가진 토큰을 반환하도록 준비합니다.
	 *
	 * @param accessToken 반환할 가짜 액세스 토큰
	 * @param expiresIn 반환할 토큰 유효시간(초)
	 */
	private void 토큰_발급_응답을_준비한다(String accessToken, long expiresIn) {
		server.expect(requestTo(BASE_URL + "/oauth2/token"))
				.andRespond(withSuccess("""
						{
						  "access_token": "%s",
						  "token_type": "Bearer",
						  "expires_in": %d
						}
						""".formatted(accessToken, expiresIn), MediaType.APPLICATION_JSON));
	}

	/**
	 * 토큰 발급 테스트에 사용할 정상적인 가짜 인증 설정을 만듭니다.
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
