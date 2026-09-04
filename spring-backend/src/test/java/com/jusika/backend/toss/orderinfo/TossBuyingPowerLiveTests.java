package com.jusika.backend.toss.orderinfo;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.jusika.backend.account.AccountResponse;
import com.jusika.backend.buyingpower.BuyingPowerResponse;
import com.jusika.backend.toss.account.TossAccountClient;

/**
 * 사용자가 명시적으로 허용했을 때만 실제 토스증권 서버에서 매수 가능 금액을 확인합니다.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "RUN_TOSS_LIVE_TEST", matches = "true")
class TossBuyingPowerLiveTests {

	@Autowired
	private TossAccountClient accountClient;

	@Autowired
	private TossBuyingPowerClient buyingPowerClient;

	/**
	 * 실제 계좌의 원화와 달러 매수 가능 금액을 조회하고 금액을 출력하지 않은 채 형식만 검사합니다.
	 */
	@Test
	@DisplayName("실제 토스증권 계좌에서 원화와 달러 매수 가능 금액을 안전하게 조회한다")
	void 실제_토스증권_계좌에서_원화와_달러_매수_가능_금액을_안전하게_조회한다() {
		List<AccountResponse> accounts = accountClient.getAccounts();
		assertThat(accounts).isNotEmpty();

		long accountSeq = accounts.getFirst().accountSeq();
		BuyingPowerResponse krwResponse = buyingPowerClient.getBuyingPower(accountSeq, "KRW");
		BuyingPowerResponse usdResponse = buyingPowerClient.getBuyingPower(accountSeq, "USD");

		assertBuyingPower(krwResponse, accountSeq, "KRW");
		assertBuyingPower(usdResponse, accountSeq, "USD");
	}

	/**
	 * 실제 응답이 요청 계좌와 통화에 일치하고 매수 가능 금액이 음수가 아닌지 검사합니다.
	 *
	 * @param response 검사할 실제 매수 가능 금액 응답
	 * @param accountSeq 요청에 사용한 계좌 식별값
	 * @param currency 요청에 사용한 통화 코드
	 */
	private void assertBuyingPower(BuyingPowerResponse response, long accountSeq, String currency) {
		assertThat(response.accountSeq()).isEqualTo(accountSeq);
		assertThat(response.currency()).isEqualTo(currency);
		assertThat(response.cashBuyingPower()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
	}
}
