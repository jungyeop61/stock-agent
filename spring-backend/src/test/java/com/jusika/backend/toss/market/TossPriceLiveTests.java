package com.jusika.backend.toss.market;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.jusika.backend.stock.StockPriceResponse;

/**
 * 사용자가 명시적으로 허용했을 때만 실제 토스증권 서버에서 삼성전자 현재가를 확인합니다.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "RUN_TOSS_LIVE_TEST", matches = "true")
class TossPriceLiveTests {

	@Autowired
	private TossPriceClient priceClient;

	/**
	 * 실제 액세스 토큰을 발급받아 삼성전자 현재가를 조회하고 필수 값만 검사합니다.
	 * 액세스 토큰이나 클라이언트 비밀키는 테스트 결과에 출력하지 않습니다.
	 */
	@Test
	@DisplayName("실제 토스증권에서 삼성전자 현재가를 안전하게 조회한다")
	void 실제_토스증권에서_삼성전자_현재가를_안전하게_조회한다() {
		StockPriceResponse response = priceClient.getCurrentPrice("005930");

		assertThat(response.symbol()).isEqualTo("005930");
		assertThat(response.price()).isPositive();
		assertThat(response.currency()).isEqualTo("KRW");
		assertThat(response.timestamp()).isNotNull();
	}
}
