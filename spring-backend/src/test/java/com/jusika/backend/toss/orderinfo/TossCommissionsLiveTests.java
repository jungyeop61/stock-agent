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
import com.jusika.backend.commission.CommissionsResponse;
import com.jusika.backend.toss.account.TossAccountClient;

/**
 * 사용자가 명시적으로 허용했을 때만 실제 토스증권 서버에서 시장별 매매 수수료를 확인합니다.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "RUN_TOSS_LIVE_TEST", matches = "true")
class TossCommissionsLiveTests {

	@Autowired
	private TossAccountClient accountClient;

	@Autowired
	private TossCommissionsClient commissionsClient;

	/**
	 * 실제 계좌의 국내와 미국 수수료를 조회하고 금융값을 출력하지 않은 채 형식만 검사합니다.
	 */
	@Test
	@DisplayName("실제 토스증권 계좌에서 국내와 미국 시장의 수수료를 안전하게 조회한다")
	void 실제_토스증권_계좌에서_국내와_미국_시장의_수수료를_안전하게_조회한다() {
		List<AccountResponse> accounts = accountClient.getAccounts();
		assertThat(accounts).isNotEmpty();

		long accountSeq = accounts.getFirst().accountSeq();
		CommissionsResponse response = commissionsClient.getCommissions(accountSeq);

		assertThat(response.accountSeq()).isEqualTo(accountSeq);
		assertThat(response.commissions()).isNotEmpty();
		assertThat(response.commissions())
				.allSatisfy(commission -> {
					assertThat(commission.marketCountry()).isIn("KR", "US");
					assertThat(commission.commissionRate()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
				});
		assertThat(response.commissions())
				.extracting(CommissionsResponse.CommissionItem::marketCountry)
				.contains("KR", "US");
	}
}
