package com.jusika.backend.toss.marketinfo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.jusika.backend.exchangerate.ExchangeRateResponse;

/**
 * 사용자가 명시적으로 허용했을 때만 실제 토스증권 서버에서 현재 USD→KRW 환율을 확인합니다.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "RUN_TOSS_LIVE_TEST", matches = "true")
class TossExchangeRateLiveTests {

	@Autowired
	private TossExchangeRateClient exchangeRateClient;

	/**
	 * 실제 액세스 토큰을 발급받아 현재 USD→KRW 환율의 필수 값만 검사합니다.
	 * 액세스 토큰이나 클라이언트 비밀키는 테스트 결과에 출력하지 않습니다.
	 */
	@Test
	@DisplayName("실제 토스증권에서 현재 달러 원화 환율을 안전하게 조회한다")
	void 실제_토스증권에서_현재_달러_원화_환율을_안전하게_조회한다() {
		ExchangeRateResponse response = exchangeRateClient.getExchangeRate("USD", "KRW", null);

		assertThat(response.baseCurrency()).isEqualTo("USD");
		assertThat(response.quoteCurrency()).isEqualTo("KRW");
		assertThat(response.rate()).isPositive();
		assertThat(response.midRate()).isPositive();
		assertThat(response.validFrom()).isBefore(response.validUntil());
	}
}
