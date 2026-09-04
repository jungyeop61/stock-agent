package com.jusika.backend.toss.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.jusika.backend.account.AccountResponse;

/**
 * 사용자가 명시적으로 허용했을 때만 실제 토스증권 서버에서 계좌 목록을 확인합니다.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "RUN_TOSS_LIVE_TEST", matches = "true")
class TossAccountLiveTests {

	@Autowired
	private TossAccountClient accountClient;

	/**
	 * 실제 액세스 토큰으로 계좌 목록을 조회하고 개인정보를 출력하지 않은 채 형식만 검사합니다.
	 */
	@Test
	@DisplayName("실제 토스증권에서 계좌 목록을 안전하게 조회한다")
	void 실제_토스증권에서_계좌_목록을_안전하게_조회한다() {
		List<AccountResponse> responses = accountClient.getAccounts();

		assertThat(responses).isNotNull();
		assertThat(responses).allSatisfy(response -> {
			assertThat(response.accountSeq()).isPositive();
			assertThat(response.maskedAccountNumber()).startsWith("*");
			assertThat(response.accountType()).isNotBlank();
		});
	}
}
