package com.jusika.backend.orderpreview;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
import com.jusika.backend.toss.orderinfo.TossSellableQuantityClient;

/**
 * 사용자가 명시적으로 허용했을 때 실제 토스증권 조회 API로 주문 미리보기를 확인합니다.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "RUN_TOSS_LIVE_TEST", matches = "true")
class OrderPreviewLiveTests {

	@Autowired
	private TossAccountClient accountClient;

	@Autowired
	private TossHoldingsClient holdingsClient;

	@Autowired
	private TossSellableQuantityClient sellableQuantityClient;

	@Autowired
	private OrderPreviewService orderPreviewService;

	/**
	 * 실제 계좌에서 매도 가능한 보유 종목을 골라 주문 전송 없이 시장가 매도 미리보기를 검사합니다.
	 */
	@Test
	@DisplayName("실제 토스증권 계좌에서 주문 전송 없이 매도 미리보기를 만든다")
	void 실제_토스증권_계좌에서_주문_전송_없이_매도_미리보기를_만든다() {
		List<AccountResponse> accounts = accountClient.getAccounts();
		assertThat(accounts).isNotEmpty();

		long accountSeq = accounts.getFirst().accountSeq();
		HoldingsResponse holdings = holdingsClient.getHoldings(accountSeq);
		PreviewTarget target = 매도_가능한_미리보기_대상을_찾는다(accountSeq, holdings.items());
		assertThat(target)
				.as("실제 미리보기 테스트에는 주문 가능한 수량이 있는 보유 종목이 필요합니다.")
				.isNotNull();

		OrderPreviewRequest request = new OrderPreviewRequest(
				accountSeq,
				target.symbol(),
				OrderSide.SELL,
				OrderType.MARKET,
				target.quantity(),
				null);
		OrderPreviewResponse response = orderPreviewService.createPreview(request);

		assertThat(response.accountSeq()).isEqualTo(accountSeq);
		assertThat(response.symbol()).isEqualToIgnoringCase(target.symbol());
		assertThat(response.side()).isEqualTo(OrderSide.SELL);
		assertThat(response.orderType()).isEqualTo(OrderType.MARKET);
		assertThat(response.quantity()).isEqualByComparingTo(target.quantity());
		assertThat(response.orderReady()).isTrue();
		assertThat(response.status()).isEqualTo(OrderPreviewStatus.PENDING_APPROVAL);
		assertThat(response.expiresAt()).isAfter(response.createdAt());
		assertThat(response.approvedAt()).isNull();
		assertThat(response.sellTaxExcluded()).isTrue();
		assertThat(response.estimatedOrderAmount()).isGreaterThan(BigDecimal.ZERO);
	}

	/**
	 * 실제 보유 종목 중 매도 가능 수량이 있는 첫 종목과 안전한 테스트 수량을 찾습니다.
	 *
	 * @param accountSeq 조회에 사용할 계좌 식별값
	 * @param holdings 실제 계좌의 보유 종목 목록
	 * @return 매도 미리보기에 사용할 종목과 수량이며 대상이 없으면 null
	 */
	private PreviewTarget 매도_가능한_미리보기_대상을_찾는다(
			long accountSeq,
			List<HoldingItem> holdings) {
		for (HoldingItem holding : holdings) {
			SellableQuantityResponse sellable =
					sellableQuantityClient.getSellableQuantity(accountSeq, holding.symbol());
			BigDecimal quantity = 안전한_테스트_수량을_선택한다(
					holding.marketCountry(), sellable.sellableQuantity());
			if (quantity != null) {
				return new PreviewTarget(holding.symbol(), quantity);
			}
		}
		return null;
	}

	/**
	 * 실제 주문은 보내지 않지만 미리보기 규칙을 통과할 가장 작은 범위의 수량을 선택합니다.
	 *
	 * @param marketCountry 보유 종목의 시장 국가 코드
	 * @param sellableQuantity 실제 매도 가능 수량
	 * @return 국내는 1주, 미국은 최대 1주의 6자리 이하 수량이며 선택할 수 없으면 null
	 */
	private BigDecimal 안전한_테스트_수량을_선택한다(
			String marketCountry,
			BigDecimal sellableQuantity) {
		if (sellableQuantity == null || sellableQuantity.signum() <= 0) {
			return null;
		}
		if ("KR".equals(marketCountry)) {
			return sellableQuantity.compareTo(BigDecimal.ONE) >= 0 ? BigDecimal.ONE : null;
		}
		if ("US".equals(marketCountry)) {
			BigDecimal quantity = sellableQuantity.min(BigDecimal.ONE)
					.setScale(6, RoundingMode.DOWN)
					.stripTrailingZeros();
			return quantity.signum() > 0 ? quantity : null;
		}
		return null;
	}

	/**
	 * 실제 미리보기에 사용할 종목 코드와 주문 수량을 함께 보관합니다.
	 *
	 * @param symbol 주문할 종목 코드
	 * @param quantity 미리보기에 사용할 주문 수량
	 */
	private record PreviewTarget(String symbol, BigDecimal quantity) {
	}
}
