package com.jusika.backend.orderpreview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.jusika.backend.buyingpower.BuyingPowerResponse;
import com.jusika.backend.commission.CommissionsResponse;
import com.jusika.backend.commission.CommissionsResponse.CommissionItem;
import com.jusika.backend.sellablequantity.SellableQuantityResponse;
import com.jusika.backend.stock.StockPriceResponse;
import com.jusika.backend.toss.market.TossPriceClient;
import com.jusika.backend.toss.orderinfo.TossBuyingPowerClient;
import com.jusika.backend.toss.orderinfo.TossCommissionsClient;
import com.jusika.backend.toss.orderinfo.TossSellableQuantityClient;

/**
 * 가짜 토스증권 조회 기능을 사용해 실제 주문 없이 주문 미리보기의 검증과 계산을 검사합니다.
 */
class OrderPreviewServiceTests {

	private static final long ACCOUNT_SEQ = 1L;
	private static final Instant FIXED_INSTANT = Instant.parse("2026-09-04T11:00:00Z");

	private FakePriceClient priceClient;
	private FakeBuyingPowerClient buyingPowerClient;
	private FakeSellableQuantityClient sellableQuantityClient;
	private FakeCommissionsClient commissionsClient;

	private OrderPreviewService orderPreviewService;

	/**
	 * 각 테스트에서 사용할 고정 시각과 가짜 토스증권 조회 기능을 준비합니다.
	 */
	@BeforeEach
	void 각_테스트에_필요한_주문_미리보기_서비스를_준비한다() {
		priceClient = new FakePriceClient();
		buyingPowerClient = new FakeBuyingPowerClient();
		sellableQuantityClient = new FakeSellableQuantityClient();
		commissionsClient = new FakeCommissionsClient();
		Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
		orderPreviewService = new OrderPreviewService(
				priceClient,
				buyingPowerClient,
				sellableQuantityClient,
				commissionsClient,
				clock);
	}

	/**
	 * 국내 지정가 매수의 주문금액과 수수료를 계산하고 매수 가능 금액을 확인하는지 검사합니다.
	 */
	@Test
	@DisplayName("국내 지정가 매수 미리보기를 실제 주문 없이 계산한다")
	void 국내_지정가_매수_미리보기를_실제_주문_없이_계산한다() {
		국내_현재가를_준비한다();
		시장_수수료를_준비한다("KR", "0.00015");
		buyingPowerClient.response =
				new BuyingPowerResponse(ACCOUNT_SEQ, "KRW", new BigDecimal("100000"));
		OrderPreviewRequest request = new OrderPreviewRequest(
				ACCOUNT_SEQ, "005930", OrderSide.BUY, OrderType.LIMIT,
				BigDecimal.ONE, new BigDecimal("70000"));

		OrderPreviewResponse response = orderPreviewService.createPreview(request);

		미리보기_식별값이_UUID인지_확인한다(response.previewId());
		assertThat(response.createdAt()).isEqualTo(OffsetDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC));
		assertThat(response.symbol()).isEqualTo("005930");
		assertThat(response.currency()).isEqualTo("KRW");
		assertThat(response.marketCountry()).isEqualTo("KR");
		assertThat(response.referencePrice()).isEqualByComparingTo("72000");
		assertThat(response.calculationPrice()).isEqualByComparingTo("70000");
		assertThat(response.estimatedOrderAmount()).isEqualByComparingTo("70000");
		assertThat(response.estimatedCommission()).isEqualByComparingTo("10.5");
		assertThat(response.estimatedAmountAfterCommission()).isEqualByComparingTo("70010.5");
		assertThat(response.sellTaxExcluded()).isFalse();
		assertThat(response.requiresHighValueConfirmation()).isFalse();
		assertThat(response.orderReady()).isTrue();
		assertThat(sellableQuantityClient.callCount).isZero();
	}

	/**
	 * 미국 시장가 매도에서 허용되는 소수 수량과 수수료 차감 금액을 계산하는지 검사합니다.
	 */
	@Test
	@DisplayName("미국 시장가 소수 수량 매도 미리보기를 계산한다")
	void 미국_시장가_소수_수량_매도_미리보기를_계산한다() {
		미국_현재가를_준비한다();
		시장_수수료를_준비한다("US", "0.001");
		sellableQuantityClient.response =
				new SellableQuantityResponse(ACCOUNT_SEQ, "AAPL", new BigDecimal("5.5"));
		OrderPreviewRequest request = new OrderPreviewRequest(
				ACCOUNT_SEQ, "aapl", OrderSide.SELL, OrderType.MARKET,
				new BigDecimal("0.5"), null);

		OrderPreviewResponse response = orderPreviewService.createPreview(request);

		assertThat(response.symbol()).isEqualTo("AAPL");
		assertThat(response.requestedPrice()).isNull();
		assertThat(response.calculationPrice()).isEqualByComparingTo("185.70");
		assertThat(response.estimatedOrderAmount()).isEqualByComparingTo("92.85");
		assertThat(response.estimatedCommission()).isEqualByComparingTo("0.09285");
		assertThat(response.estimatedAmountAfterCommission()).isEqualByComparingTo("92.75715");
		assertThat(response.sellTaxExcluded()).isTrue();
		assertThat(response.orderReady()).isTrue();
		assertThat(buyingPowerClient.callCount).isZero();
	}

	/**
	 * 국내 주문금액이 1억원 이상이면 실제 주문 전에 추가 확인이 필요하다고 표시하는지 검사합니다.
	 */
	@Test
	@DisplayName("국내 1억원 이상 주문에 추가 확인 필요를 표시한다")
	void 국내_1억원_이상_주문에_추가_확인_필요를_표시한다() {
		국내_현재가를_준비한다();
		시장_수수료를_준비한다("KR", "0.00015");
		buyingPowerClient.response =
				new BuyingPowerResponse(ACCOUNT_SEQ, "KRW", new BigDecimal("200000000"));
		OrderPreviewRequest request = new OrderPreviewRequest(
				ACCOUNT_SEQ, "005930", OrderSide.BUY, OrderType.LIMIT,
				new BigDecimal("1000"), new BigDecimal("100000"));

		OrderPreviewResponse response = orderPreviewService.createPreview(request);

		assertThat(response.estimatedOrderAmount()).isEqualByComparingTo("100000000");
		assertThat(response.requiresHighValueConfirmation()).isTrue();
	}

	/**
	 * 시장가 주문에 사용자가 가격을 넣으면 외부 주문 없이 거절하는지 검사합니다.
	 */
	@Test
	@DisplayName("시장가 주문에 입력한 가격을 거절한다")
	void 시장가_주문에_입력한_가격을_거절한다() {
		국내_현재가를_준비한다();
		OrderPreviewRequest request = new OrderPreviewRequest(
				ACCOUNT_SEQ, "005930", OrderSide.BUY, OrderType.MARKET,
				BigDecimal.ONE, new BigDecimal("70000"));

		assertThatThrownBy(() -> orderPreviewService.createPreview(request))
				.isInstanceOf(OrderPreviewException.class)
				.hasMessage("시장가 주문에는 가격을 입력할 수 없습니다.");
		assertThat(commissionsClient.callCount).isZero();
	}

	/**
	 * 지정가 주문에 가격이 없으면 외부 주문 없이 거절하는지 검사합니다.
	 */
	@Test
	@DisplayName("가격이 없는 지정가 주문을 거절한다")
	void 가격이_없는_지정가_주문을_거절한다() {
		국내_현재가를_준비한다();
		OrderPreviewRequest request = new OrderPreviewRequest(
				ACCOUNT_SEQ, "005930", OrderSide.BUY, OrderType.LIMIT,
				BigDecimal.ONE, null);

		assertThatThrownBy(() -> orderPreviewService.createPreview(request))
				.isInstanceOf(OrderPreviewException.class)
				.hasMessage("지정가 주문에는 0보다 큰 가격이 필요합니다.");
		assertThat(commissionsClient.callCount).isZero();
	}

	/**
	 * 국내 주식의 소수 수량을 토스증권 조회 단계에서 차단하는지 검사합니다.
	 */
	@Test
	@DisplayName("국내 주식의 소수 수량 주문을 거절한다")
	void 국내_주식의_소수_수량_주문을_거절한다() {
		국내_현재가를_준비한다();
		OrderPreviewRequest request = new OrderPreviewRequest(
				ACCOUNT_SEQ, "005930", OrderSide.SELL, OrderType.MARKET,
				new BigDecimal("0.5"), null);

		assertThatThrownBy(() -> orderPreviewService.createPreview(request))
				.isInstanceOf(OrderPreviewException.class)
				.hasMessage("소수점 수량은 미국 주식 시장가 매도에만 사용할 수 있습니다.");
		assertThat(sellableQuantityClient.callCount).isZero();
	}

	/**
	 * 미국 주식이라도 지정가 매수의 소수 수량은 금액 주문 대상이므로 거절하는지 검사합니다.
	 */
	@Test
	@DisplayName("미국 지정가 매수의 소수 수량 주문을 거절한다")
	void 미국_지정가_매수의_소수_수량_주문을_거절한다() {
		미국_현재가를_준비한다();
		OrderPreviewRequest request = new OrderPreviewRequest(
				ACCOUNT_SEQ, "AAPL", OrderSide.BUY, OrderType.LIMIT,
				new BigDecimal("0.5"), new BigDecimal("185.5"));

		assertThatThrownBy(() -> orderPreviewService.createPreview(request))
				.isInstanceOf(OrderPreviewException.class)
				.hasMessage("소수점 수량은 미국 주식 시장가 매도에만 사용할 수 있습니다.");
	}

	/**
	 * 미국 시장가 매도의 소수 수량이 6자리를 넘으면 거절하는지 검사합니다.
	 */
	@Test
	@DisplayName("미국 시장가 매도의 소수점 6자리 초과 수량을 거절한다")
	void 미국_시장가_매도의_소수점_6자리_초과_수량을_거절한다() {
		미국_현재가를_준비한다();
		OrderPreviewRequest request = new OrderPreviewRequest(
				ACCOUNT_SEQ, "AAPL", OrderSide.SELL, OrderType.MARKET,
				new BigDecimal("0.1234567"), null);

		assertThatThrownBy(() -> orderPreviewService.createPreview(request))
				.isInstanceOf(OrderPreviewException.class)
				.hasMessage("미국 주식 소수점 수량은 소수점 6자리까지 사용할 수 있습니다.");
	}

	/**
	 * 매수에 필요한 예상 금액과 수수료 합계가 현금보다 크면 미리보기를 만들지 않는지 검사합니다.
	 */
	@Test
	@DisplayName("매수 가능 금액이 부족한 주문을 거절한다")
	void 매수_가능_금액이_부족한_주문을_거절한다() {
		국내_현재가를_준비한다();
		시장_수수료를_준비한다("KR", "0.00015");
		buyingPowerClient.response =
				new BuyingPowerResponse(ACCOUNT_SEQ, "KRW", new BigDecimal("70000"));
		OrderPreviewRequest request = new OrderPreviewRequest(
				ACCOUNT_SEQ, "005930", OrderSide.BUY, OrderType.LIMIT,
				BigDecimal.ONE, new BigDecimal("70000"));

		assertThatThrownBy(() -> orderPreviewService.createPreview(request))
				.isInstanceOf(OrderPreviewException.class)
				.hasMessage("매수 가능 금액이 부족합니다.");
	}

	/**
	 * 매도 주문 수량이 지금 매도 가능한 수량보다 크면 미리보기를 만들지 않는지 검사합니다.
	 */
	@Test
	@DisplayName("매도 가능 수량이 부족한 주문을 거절한다")
	void 매도_가능_수량이_부족한_주문을_거절한다() {
		국내_현재가를_준비한다();
		sellableQuantityClient.response =
				new SellableQuantityResponse(ACCOUNT_SEQ, "005930", new BigDecimal("2"));
		OrderPreviewRequest request = new OrderPreviewRequest(
				ACCOUNT_SEQ, "005930", OrderSide.SELL, OrderType.MARKET,
				new BigDecimal("3"), null);

		assertThatThrownBy(() -> orderPreviewService.createPreview(request))
				.isInstanceOf(OrderPreviewException.class)
				.hasMessage("매도 가능 수량이 부족합니다.");
		assertThat(commissionsClient.callCount).isZero();
	}

	/**
	 * 종목 시장에 해당하는 수수료가 없으면 잘못된 시장의 수수료를 사용하지 않는지 검사합니다.
	 */
	@Test
	@DisplayName("해당 시장의 수수료가 없는 주문을 거절한다")
	void 해당_시장의_수수료가_없는_주문을_거절한다() {
		국내_현재가를_준비한다();
		시장_수수료를_준비한다("US", "0.001");
		OrderPreviewRequest request = new OrderPreviewRequest(
				ACCOUNT_SEQ, "005930", OrderSide.BUY, OrderType.MARKET,
				BigDecimal.ONE, null);

		assertThatThrownBy(() -> orderPreviewService.createPreview(request))
				.isInstanceOf(OrderPreviewException.class)
				.hasMessage("해당 시장의 매매 수수료를 찾지 못했습니다.");
		assertThat(buyingPowerClient.callCount).isZero();
	}

	/**
	 * 지원하지 않는 통화가 현재가에 포함되면 계좌 금액 계산을 중단하는지 검사합니다.
	 */
	@Test
	@DisplayName("지원하지 않는 거래 통화의 주문을 거절한다")
	void 지원하지_않는_거래_통화의_주문을_거절한다() {
		priceClient.response = new StockPriceResponse(
				"7203", new BigDecimal("1000"), "JPY",
				OffsetDateTime.parse("2026-09-04T20:00:00+09:00"));
		OrderPreviewRequest request = new OrderPreviewRequest(
				ACCOUNT_SEQ, "7203", OrderSide.BUY, OrderType.MARKET,
				BigDecimal.ONE, null);

		assertThatThrownBy(() -> orderPreviewService.createPreview(request))
				.isInstanceOf(OrderPreviewException.class)
				.hasMessage("지원하지 않는 거래 통화입니다.");
	}

	/**
	 * 국내 주식 현재가를 반환하도록 가짜 시세 조회 기능을 준비합니다.
	 */
	private void 국내_현재가를_준비한다() {
		priceClient.response = new StockPriceResponse(
				"005930", new BigDecimal("72000"), "KRW",
				OffsetDateTime.parse("2026-09-04T20:00:00+09:00"));
	}

	/**
	 * 미국 주식 현재가를 반환하도록 가짜 시세 조회 기능을 준비합니다.
	 */
	private void 미국_현재가를_준비한다() {
		priceClient.response = new StockPriceResponse(
				"AAPL", new BigDecimal("185.70"), "USD",
				OffsetDateTime.parse("2026-09-04T20:00:00+09:00"));
	}

	/**
	 * 지정한 시장의 수수료율을 반환하도록 가짜 수수료 조회 기능을 준비합니다.
	 *
	 * @param marketCountry 반환할 시장 국가 코드
	 * @param commissionRate 반환할 소수 비율 형태의 수수료율
	 */
	private void 시장_수수료를_준비한다(String marketCountry, String commissionRate) {
		CommissionItem item = new CommissionItem(
				marketCountry,
				new BigDecimal(commissionRate),
				LocalDate.of(2026, 1, 1),
				null);
		commissionsClient.response = new CommissionsResponse(ACCOUNT_SEQ, List.of(item));
	}

	/**
	 * 미리보기 임시 식별값이 표준 UUID 문자열인지 확인합니다.
	 *
	 * @param previewId 검사할 미리보기 식별값
	 */
	private void 미리보기_식별값이_UUID인지_확인한다(String previewId) {
		assertThat(UUID.fromString(previewId)).isNotNull();
	}

	/**
	 * 테스트에서 준비한 현재가만 반환하고 실제 토스증권 서버는 호출하지 않습니다.
	 */
	private static final class FakePriceClient extends TossPriceClient {

		private StockPriceResponse response;
		private int callCount;

		/**
		 * 부모 클라이언트의 네트워크 의존성 없이 테스트 대역을 만듭니다.
		 */
		private FakePriceClient() {
			super(null, null);
		}

		/**
		 * 준비된 현재가를 반환하고 호출 횟수를 기록합니다.
		 *
		 * @param symbol 조회 요청을 받은 종목 코드
		 * @return 테스트에서 준비한 현재가
		 */
		@Override
		public StockPriceResponse getCurrentPrice(String symbol) {
			callCount++;
			if (response == null) {
				throw new AssertionError("테스트 현재가 응답이 준비되지 않았습니다.");
			}
			return response;
		}
	}

	/**
	 * 테스트에서 준비한 매수 가능 금액만 반환하고 실제 토스증권 서버는 호출하지 않습니다.
	 */
	private static final class FakeBuyingPowerClient extends TossBuyingPowerClient {

		private BuyingPowerResponse response;
		private int callCount;

		/**
		 * 부모 클라이언트의 네트워크 의존성 없이 테스트 대역을 만듭니다.
		 */
		private FakeBuyingPowerClient() {
			super(null, null);
		}

		/**
		 * 준비된 매수 가능 금액을 반환하고 호출 횟수를 기록합니다.
		 *
		 * @param accountSeq 조회 요청을 받은 계좌 식별값
		 * @param currency 조회 요청을 받은 통화 코드
		 * @return 테스트에서 준비한 매수 가능 금액
		 */
		@Override
		public BuyingPowerResponse getBuyingPower(long accountSeq, String currency) {
			callCount++;
			if (response == null) {
				throw new AssertionError("테스트 매수 가능 금액 응답이 준비되지 않았습니다.");
			}
			return response;
		}
	}

	/**
	 * 테스트에서 준비한 매도 가능 수량만 반환하고 실제 토스증권 서버는 호출하지 않습니다.
	 */
	private static final class FakeSellableQuantityClient extends TossSellableQuantityClient {

		private SellableQuantityResponse response;
		private int callCount;

		/**
		 * 부모 클라이언트의 네트워크 의존성 없이 테스트 대역을 만듭니다.
		 */
		private FakeSellableQuantityClient() {
			super(null, null);
		}

		/**
		 * 준비된 매도 가능 수량을 반환하고 호출 횟수를 기록합니다.
		 *
		 * @param accountSeq 조회 요청을 받은 계좌 식별값
		 * @param symbol 조회 요청을 받은 종목 코드
		 * @return 테스트에서 준비한 매도 가능 수량
		 */
		@Override
		public SellableQuantityResponse getSellableQuantity(long accountSeq, String symbol) {
			callCount++;
			if (response == null) {
				throw new AssertionError("테스트 매도 가능 수량 응답이 준비되지 않았습니다.");
			}
			return response;
		}
	}

	/**
	 * 테스트에서 준비한 시장별 수수료만 반환하고 실제 토스증권 서버는 호출하지 않습니다.
	 */
	private static final class FakeCommissionsClient extends TossCommissionsClient {

		private CommissionsResponse response;
		private int callCount;

		/**
		 * 부모 클라이언트의 네트워크 의존성 없이 테스트 대역을 만듭니다.
		 */
		private FakeCommissionsClient() {
			super(null, null);
		}

		/**
		 * 준비된 시장별 수수료를 반환하고 호출 횟수를 기록합니다.
		 *
		 * @param accountSeq 조회 요청을 받은 계좌 식별값
		 * @return 테스트에서 준비한 시장별 수수료
		 */
		@Override
		public CommissionsResponse getCommissions(long accountSeq) {
			callCount++;
			if (response == null) {
				throw new AssertionError("테스트 수수료 응답이 준비되지 않았습니다.");
			}
			return response;
		}
	}
}
