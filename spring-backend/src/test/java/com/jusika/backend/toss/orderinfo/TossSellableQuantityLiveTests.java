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
import com.jusika.backend.holding.HoldingsResponse;
import com.jusika.backend.holding.HoldingsResponse.HoldingItem;
import com.jusika.backend.sellablequantity.SellableQuantityResponse;
import com.jusika.backend.toss.account.TossAccountClient;
import com.jusika.backend.toss.asset.TossHoldingsClient;

/**
 * 사용자가 명시적으로 허용했을 때만 실제 토스증권 서버에서 매도 가능 수량을 확인합니다.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "RUN_TOSS_LIVE_TEST", matches = "true")
class TossSellableQuantityLiveTests {

	@Autowired
	private TossAccountClient accountClient;

	@Autowired
	private TossHoldingsClient holdingsClient;

	@Autowired
	private TossSellableQuantityClient sellableQuantityClient;

	/**
	 * 실제 계좌의 첫 보유 종목을 선택해 매도 가능 수량을 조회하고 금융값을 출력하지 않은 채 검사합니다.
	 */
	@Test
	@DisplayName("실제 토스증권 계좌에서 보유 종목의 매도 가능 수량을 안전하게 조회한다")
	void 실제_토스증권_계좌에서_보유_종목의_매도_가능_수량을_안전하게_조회한다() {
		List<AccountResponse> accounts = accountClient.getAccounts();
		assertThat(accounts).isNotEmpty();

		long accountSeq = accounts.getFirst().accountSeq();
		HoldingsResponse holdings = holdingsClient.getHoldings(accountSeq);
		assertThat(holdings.items())
				.as("실제 매도 가능 수량 테스트에는 최소 한 개의 보유 종목이 필요합니다.")
				.isNotEmpty();

		HoldingItem holding = holdings.items().getFirst();
		SellableQuantityResponse response =
				sellableQuantityClient.getSellableQuantity(accountSeq, holding.symbol());

		assertThat(response.accountSeq()).isEqualTo(accountSeq);
		assertThat(response.symbol()).isEqualToIgnoringCase(holding.symbol());
		assertThat(response.sellableQuantity()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
		assertThat(response.sellableQuantity()).isLessThanOrEqualTo(holding.quantity());
	}
}
