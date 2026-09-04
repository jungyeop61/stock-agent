package com.jusika.backend.toss.asset;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.jusika.backend.account.AccountResponse;
import com.jusika.backend.holding.HoldingsResponse;
import com.jusika.backend.toss.account.TossAccountClient;

/**
 * 사용자가 명시적으로 허용했을 때만 실제 토스증권 서버에서 보유주식을 확인합니다.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "RUN_TOSS_LIVE_TEST", matches = "true")
class TossHoldingsLiveTests {

	@Autowired
	private TossAccountClient accountClient;

	@Autowired
	private TossHoldingsClient holdingsClient;

	/**
	 * 실제 계좌 목록에서 식별값을 선택해 보유주식을 조회하고 금융값을 출력하지 않은 채 형식만 검사합니다.
	 */
	@Test
	@DisplayName("실제 토스증권 계좌에서 보유주식과 평가손익을 안전하게 조회한다")
	void 실제_토스증권_계좌에서_보유주식과_평가손익을_안전하게_조회한다() {
		List<AccountResponse> accounts = accountClient.getAccounts();
		assertThat(accounts).isNotEmpty();

		long accountSeq = accounts.getFirst().accountSeq();
		HoldingsResponse response = holdingsClient.getHoldings(accountSeq);

		assertThat(response.accountSeq()).isEqualTo(accountSeq);
		assertThat(response.totalPurchaseAmount()).isNotNull();
		assertThat(response.marketValue()).isNotNull();
		assertThat(response.profitLoss()).isNotNull();
		assertThat(response.dailyProfitLoss()).isNotNull();
		assertThat(response.items()).allSatisfy(item -> {
			assertThat(item.symbol()).isNotBlank();
			assertThat(item.name()).isNotBlank();
			assertThat(item.quantity()).isPositive();
			assertThat(item.currency()).isNotBlank();
		});
	}
}
