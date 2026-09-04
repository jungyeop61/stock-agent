package com.jusika.backend.toss.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 사용자가 명시적으로 허용했을 때만 실제 토스증권 OAuth 서버와 연결을 확인합니다.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "RUN_TOSS_LIVE_TEST", matches = "true")
class TossAuthLiveTests {

	@Autowired
	private TossAuthClient authClient;

	/**
	 * 로컬 환경변수의 실제 인증정보로 토큰을 한 번 발급받고 형식만 확인합니다.
	 * 액세스 토큰 문자열은 테스트 결과나 로그에 출력하지 않습니다.
	 */
	@Test
	@DisplayName("실제 토스증권에서 액세스 토큰을 안전하게 발급받는다")
	void 실제_토스증권에서_액세스_토큰을_안전하게_발급받는다() {
		TossTokenResponse response = authClient.issueAccessToken();

		assertThat(response.accessToken()).isNotBlank();
		assertThat(response.tokenType()).isEqualTo("Bearer");
		assertThat(response.expiresIn()).isPositive();
	}
}
