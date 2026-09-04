package com.jusika.backend.orderpreview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
	private FakeOrderPreviewStore previewStore;

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
		previewStore = new FakeOrderPreviewStore();
		Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
		orderPreviewService = new OrderPreviewService(
				priceClient,
				buyingPowerClient,
				sellableQuantityClient,
				commissionsClient,
				previewStore,
				new OrderPreviewProperties(Duration.ofMinutes(2)),
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
		assertThat(response.expiresAt()).isEqualTo(response.createdAt().plusMinutes(2));
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
		assertThat(response.status()).isEqualTo(OrderPreviewStatus.PENDING_APPROVAL);
		assertThat(response.approvedAt()).isNull();
		assertThat(previewStore.findById(response.previewId())).contains(response);
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
	 * 저장된 주문 내용은 바꾸지 않고 상태와 승인 시각만 한 번 기록하는지 검사합니다.
	 */
	@Test
	@DisplayName("승인 가능한 주문 미리보기의 상태만 승인으로 변경한다")
	void 승인_가능한_주문_미리보기의_상태만_승인으로_변경한다() {
		국내_현재가를_준비한다();
		시장_수수료를_준비한다("KR", "0.00015");
		buyingPowerClient.response =
				new BuyingPowerResponse(ACCOUNT_SEQ, "KRW", new BigDecimal("100000"));
		OrderPreviewResponse pending = orderPreviewService.createPreview(new OrderPreviewRequest(
				ACCOUNT_SEQ, "005930", OrderSide.BUY, OrderType.LIMIT,
				BigDecimal.ONE, new BigDecimal("70000")));

		OrderPreviewResponse approved = orderPreviewService.approvePreview(pending.previewId());

		assertThat(approved.status()).isEqualTo(OrderPreviewStatus.APPROVED);
		assertThat(approved.approvedAt()).isEqualTo(pending.createdAt());
		assertThat(approved.previewId()).isEqualTo(pending.previewId());
		assertThat(approved.accountSeq()).isEqualTo(pending.accountSeq());
		assertThat(approved.symbol()).isEqualTo(pending.symbol());
		assertThat(approved.quantity()).isEqualByComparingTo(pending.quantity());
		assertThat(approved.calculationPrice()).isEqualByComparingTo(pending.calculationPrice());
		assertThat(approved.estimatedAmountAfterCommission())
				.isEqualByComparingTo(pending.estimatedAmountAfterCommission());
	}

	/**
	 * 같은 미리보기를 다시 승인하려 하면 중복 승인으로 거절하는지 검사합니다.
	 */
	@Test
	@DisplayName("이미 승인한 주문 미리보기의 중복 승인을 거절한다")
	void 이미_승인한_주문_미리보기의_중복_승인을_거절한다() {
		국내_현재가를_준비한다();
		시장_수수료를_준비한다("KR", "0.00015");
		buyingPowerClient.response =
				new BuyingPowerResponse(ACCOUNT_SEQ, "KRW", new BigDecimal("100000"));
		OrderPreviewResponse pending = orderPreviewService.createPreview(new OrderPreviewRequest(
				ACCOUNT_SEQ, "005930", OrderSide.BUY, OrderType.LIMIT,
				BigDecimal.ONE, new BigDecimal("70000")));
		orderPreviewService.approvePreview(pending.previewId());

		assertThatThrownBy(() -> orderPreviewService.approvePreview(pending.previewId()))
				.isInstanceOf(OrderPreviewStateException.class)
				.hasMessage("이미 승인한 주문 미리보기입니다.");
	}

	/**
	 * 미리보기 유효시간이 지난 뒤에는 승인하지 않고 만료 상태로 바꾸는지 검사합니다.
	 */
	@Test
	@DisplayName("유효시간이 지난 주문 미리보기의 승인을 거절한다")
	void 유효시간이_지난_주문_미리보기의_승인을_거절한다() {
		국내_현재가를_준비한다();
		시장_수수료를_준비한다("KR", "0.00015");
		buyingPowerClient.response =
				new BuyingPowerResponse(ACCOUNT_SEQ, "KRW", new BigDecimal("100000"));
		OrderPreviewResponse pending = orderPreviewService.createPreview(new OrderPreviewRequest(
				ACCOUNT_SEQ, "005930", OrderSide.BUY, OrderType.LIMIT,
				BigDecimal.ONE, new BigDecimal("70000")));
		orderPreviewService = 미리보기_서비스를_현재시각으로_다시_만든다(FIXED_INSTANT.plusSeconds(121));

		assertThatThrownBy(() -> orderPreviewService.approvePreview(pending.previewId()))
				.isInstanceOf(OrderPreviewExpiredException.class)
				.hasMessage("주문 미리보기의 승인 시간이 지났습니다. 새 미리보기를 만들어 주세요.");
		assertThat(previewStore.findById(pending.previewId()))
				.get()
				.extracting(OrderPreviewResponse::status)
				.isEqualTo(OrderPreviewStatus.EXPIRED);
	}

	/**
	 * 올바른 UUID라도 저장된 미리보기가 없으면 찾을 수 없음으로 처리하는지 검사합니다.
	 */
	@Test
	@DisplayName("존재하지 않는 주문 미리보기의 승인을 거절한다")
	void 존재하지_않는_주문_미리보기의_승인을_거절한다() {
		String missingPreviewId = UUID.randomUUID().toString();

		assertThatThrownBy(() -> orderPreviewService.approvePreview(missingPreviewId))
				.isInstanceOf(OrderPreviewNotFoundException.class)
				.hasMessage("주문 미리보기를 찾을 수 없습니다.");
	}

	/**
	 * 승인 URL에 UUID가 아닌 문자열이 들어오면 저장소를 조회하지 않고 거절하는지 검사합니다.
	 */
	@Test
	@DisplayName("형식이 잘못된 주문 미리보기 식별값을 거절한다")
	void 형식이_잘못된_주문_미리보기_식별값을_거절한다() {
		assertThatThrownBy(() -> orderPreviewService.approvePreview("not-a-uuid"))
				.isInstanceOf(OrderPreviewException.class)
				.hasMessage("주문 미리보기 식별값 형식이 올바르지 않습니다.");
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
	 * 같은 가짜 조회 기능과 저장소를 유지한 채 승인 판단에 사용할 현재 시각만 바꿉니다.
	 *
	 * @param instant 새로 적용할 현재 시각
	 * @return 바뀐 시각을 사용하는 주문 미리보기 서비스
	 */
	private OrderPreviewService 미리보기_서비스를_현재시각으로_다시_만든다(Instant instant) {
		return new OrderPreviewService(
				priceClient,
				buyingPowerClient,
				sellableQuantityClient,
				commissionsClient,
				previewStore,
				new OrderPreviewProperties(Duration.ofMinutes(2)),
				Clock.fixed(instant, ZoneOffset.UTC));
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

	/**
	 * 테스트 메모리 안에서 미리보기 저장과 조건부 상태 변경을 흉내 냅니다.
	 */
	private static final class FakeOrderPreviewStore implements OrderPreviewStore {

		private final Map<String, OrderPreviewResponse> previews = new HashMap<>();

		/**
		 * 미리보기 전체 내용을 식별값으로 보관합니다.
		 *
		 * @param preview 저장할 주문 미리보기
		 * @return 저장된 주문 미리보기
		 */
		@Override
		public OrderPreviewResponse save(OrderPreviewResponse preview) {
			previews.put(preview.previewId(), preview);
			return preview;
		}

		/**
		 * 승인 대기 중이고 만료 전인 미리보기만 승인 상태로 변경합니다.
		 *
		 * @param previewId 승인할 미리보기 식별값
		 * @param approvedAt 승인 시각
		 * @return 이번 호출이 상태를 변경했으면 true
		 */
		@Override
		public boolean approvePending(String previewId, OffsetDateTime approvedAt) {
			OrderPreviewResponse preview = previews.get(previewId);
			if (preview == null
					|| preview.status() != OrderPreviewStatus.PENDING_APPROVAL
					|| !preview.expiresAt().isAfter(approvedAt)) {
				return false;
			}
			previews.put(previewId, 상태를_변경한다(preview, OrderPreviewStatus.APPROVED, approvedAt));
			return true;
		}

		/**
		 * 승인 대기 중이고 유효시간이 지난 미리보기만 만료 상태로 변경합니다.
		 *
		 * @param previewId 만료 여부를 반영할 미리보기 식별값
		 * @param now 현재 시각
		 * @return 이번 호출이 상태를 변경했으면 true
		 */
		@Override
		public boolean expirePending(String previewId, OffsetDateTime now) {
			OrderPreviewResponse preview = previews.get(previewId);
			if (preview == null
					|| preview.status() != OrderPreviewStatus.PENDING_APPROVAL
					|| preview.expiresAt().isAfter(now)) {
				return false;
			}
			previews.put(previewId, 상태를_변경한다(preview, OrderPreviewStatus.EXPIRED, null));
			return true;
		}

		/**
		 * 승인된 미리보기를 테스트 메모리에서 사용 완료 상태로 변경합니다.
		 *
		 * @param previewId 사용 처리할 미리보기 식별값
		 * @param consumedAt 주문 실행이 시작된 시각
		 * @return 이번 호출이 상태를 변경했으면 true
		 */
		@Override
		public boolean consumeApproved(String previewId, OffsetDateTime consumedAt) {
			OrderPreviewResponse preview = previews.get(previewId);
			if (preview == null || preview.status() != OrderPreviewStatus.APPROVED) {
				return false;
			}
			previews.put(previewId, 상태를_변경한다(preview, OrderPreviewStatus.CONSUMED, preview.approvedAt()));
			return true;
		}

		/**
		 * 식별값으로 테스트 메모리에 저장된 미리보기를 조회합니다.
		 *
		 * @param previewId 조회할 미리보기 식별값
		 * @return 저장된 미리보기이며 없으면 빈 값
		 */
		@Override
		public Optional<OrderPreviewResponse> findById(String previewId) {
			return Optional.ofNullable(previews.get(previewId));
		}

		/**
		 * 주문 계산 내용은 그대로 두고 상태와 승인 시각만 바꾼 복사본을 만듭니다.
		 *
		 * @param preview 원본 주문 미리보기
		 * @param status 새로 적용할 상태
		 * @param approvedAt 승인 시각이며 승인 상태가 아니면 null
		 * @return 상태만 바뀐 주문 미리보기
		 */
		private OrderPreviewResponse 상태를_변경한다(
				OrderPreviewResponse preview,
				OrderPreviewStatus status,
				OffsetDateTime approvedAt) {
			return new OrderPreviewResponse(
					preview.previewId(),
					preview.createdAt(),
					preview.expiresAt(),
					preview.accountSeq(),
					preview.symbol(),
					preview.side(),
					preview.orderType(),
					preview.quantity(),
					preview.requestedPrice(),
					preview.referencePrice(),
					preview.calculationPrice(),
					preview.currency(),
					preview.marketCountry(),
					preview.commissionRate(),
					preview.estimatedOrderAmount(),
					preview.estimatedCommission(),
					preview.estimatedAmountAfterCommission(),
					preview.sellTaxExcluded(),
					preview.requiresHighValueConfirmation(),
					preview.orderReady(),
					status,
					approvedAt);
		}
	}
}
