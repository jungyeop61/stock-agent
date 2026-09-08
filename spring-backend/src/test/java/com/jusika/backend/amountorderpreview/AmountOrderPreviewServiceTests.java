package com.jusika.backend.amountorderpreview;

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
import com.jusika.backend.exchangerate.ExchangeRateChangeType;
import com.jusika.backend.exchangerate.ExchangeRateResponse;
import com.jusika.backend.orderpreview.OrderPreviewProperties;
import com.jusika.backend.orderpreview.OrderPreviewStatus;
import com.jusika.backend.orderpreview.OrderSide;
import com.jusika.backend.orderpreview.OrderType;
import com.jusika.backend.stock.StockPriceResponse;
import com.jusika.backend.toss.market.TossPriceClient;
import com.jusika.backend.toss.marketinfo.TossExchangeRateClient;
import com.jusika.backend.toss.orderinfo.TossBuyingPowerClient;
import com.jusika.backend.toss.orderinfo.TossCommissionsClient;

/**
 * 가짜 읽기 전용 클라이언트와 메모리 저장소로 달러 금액 주문 미리보기의 검증과 계산을 검사합니다.
 */
class AmountOrderPreviewServiceTests {

	private static final long ACCOUNT_SEQ = 1L;
	private static final Instant FIXED_INSTANT = Instant.parse("2026-09-08T00:30:30Z");

	private FixedPriceClient priceClient;
	private FixedBuyingPowerClient buyingPowerClient;
	private FixedCommissionsClient commissionsClient;
	private FixedExchangeRateClient exchangeRateClient;
	private MemoryAmountOrderPreviewStore previewStore;
	private AmountOrderPreviewService previewService;

	/**
	 * 각 테스트에서 사용할 고정 시각과 가짜 조회 기능, 메모리 저장소를 준비합니다.
	 */
	@BeforeEach
	void 각_테스트에_필요한_금액_주문_미리보기_서비스를_준비한다() {
		priceClient = new FixedPriceClient();
		buyingPowerClient = new FixedBuyingPowerClient();
		commissionsClient = new FixedCommissionsClient();
		exchangeRateClient = new FixedExchangeRateClient();
		previewStore = new MemoryAmountOrderPreviewStore();
		previewService = new AmountOrderPreviewService(
				priceClient,
				buyingPowerClient,
				commissionsClient,
				exchangeRateClient,
				previewStore,
				new OrderPreviewProperties(Duration.ofMinutes(2)),
				Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
		정상_조회_결과를_준비한다();
	}

	/**
	 * 미국 주식 달러 금액 매수의 예상 수량과 수수료, 원화 환산액을 계산해 저장하는지 검사합니다.
	 */
	@Test
	@DisplayName("미국 주식 달러 금액 시장가 매수 미리보기를 계산한다")
	void 미국_주식_달러_금액_시장가_매수_미리보기를_계산한다() {
		AmountOrderPreviewResponse response = previewService.createPreview(
				new AmountOrderPreviewRequest(ACCOUNT_SEQ, "aapl", new BigDecimal("100")));

		assertThat(UUID.fromString(response.previewId())).isNotNull();
		assertThat(response.createdAt()).isEqualTo(OffsetDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC));
		assertThat(response.expiresAt()).isEqualTo(response.createdAt().plusMinutes(2));
		assertThat(response.symbol()).isEqualTo("AAPL");
		assertThat(response.side()).isEqualTo(OrderSide.BUY);
		assertThat(response.orderType()).isEqualTo(OrderType.MARKET);
		assertThat(response.currency()).isEqualTo("USD");
		assertThat(response.marketCountry()).isEqualTo("US");
		assertThat(response.orderAmount()).isEqualByComparingTo("100");
		assertThat(response.referencePrice()).isEqualByComparingTo("200");
		assertThat(response.estimatedQuantity()).isEqualByComparingTo("0.5");
		assertThat(response.commissionRate()).isEqualByComparingTo("0.001");
		assertThat(response.estimatedCommission()).isEqualByComparingTo("0.1");
		assertThat(response.estimatedTotalCost()).isEqualByComparingTo("100.1");
		assertThat(response.exchangeRate()).isEqualByComparingTo("1400");
		assertThat(response.estimatedOrderAmountKrw()).isEqualByComparingTo("140000");
		assertThat(response.requiresHighValueConfirmation()).isFalse();
		assertThat(response.orderReady()).isTrue();
		assertThat(response.status()).isEqualTo(OrderPreviewStatus.PENDING_APPROVAL);
		assertThat(previewStore.findById(response.previewId())).contains(response);
		assertThat(priceClient.callCount).isEqualTo(1);
		assertThat(buyingPowerClient.requestedCurrency).isEqualTo("USD");
		assertThat(commissionsClient.callCount).isEqualTo(1);
		assertThat(exchangeRateClient.requestedBaseCurrency).isEqualTo("USD");
		assertThat(exchangeRateClient.requestedQuoteCurrency).isEqualTo("KRW");
	}

	/**
	 * 원화 환산 주문금액이 정확히 1억원이면 고액 주문 확인 대상으로 표시하는지 검사합니다.
	 */
	@Test
	@DisplayName("원화 환산액 1억원 이상을 고액 주문으로 표시한다")
	void 원화_환산액_1억원_이상을_고액_주문으로_표시한다() {
		commissionsClient.response = 수수료_응답을_만든다("0");
		buyingPowerClient.response = new BuyingPowerResponse(
				ACCOUNT_SEQ, "USD", new BigDecimal("200000"));
		exchangeRateClient.response = 환율_응답을_만든다("1000", "2026-09-08T09:31:00+09:00");

		AmountOrderPreviewResponse response = previewService.createPreview(
				new AmountOrderPreviewRequest(ACCOUNT_SEQ, "AAPL", new BigDecimal("100000")));

		assertThat(response.estimatedOrderAmountKrw()).isEqualByComparingTo("100000000");
		assertThat(response.requiresHighValueConfirmation()).isTrue();
	}

	/**
	 * 원화 환산 주문금액이 프로젝트 상한인 30억원을 넘으면 저장하지 않는지 검사합니다.
	 */
	@Test
	@DisplayName("원화 환산액 30억원 초과 주문을 거절한다")
	void 원화_환산액_30억원_초과_주문을_거절한다() {
		commissionsClient.response = 수수료_응답을_만든다("0");
		buyingPowerClient.response = new BuyingPowerResponse(
				ACCOUNT_SEQ, "USD", new BigDecimal("4000000"));
		exchangeRateClient.response = 환율_응답을_만든다("1000", "2026-09-08T09:31:00+09:00");

		assertThatThrownBy(() -> previewService.createPreview(
				new AmountOrderPreviewRequest(
						ACCOUNT_SEQ, "AAPL", new BigDecimal("3000000.01"))))
				.isInstanceOf(AmountOrderPreviewException.class)
				.hasMessage("원화 환산 주문금액은 30억원을 초과할 수 없습니다.");
		assertThat(previewStore.previews).isEmpty();
	}

	/**
	 * 수수료를 포함한 필요 금액이 달러 현금 매수 가능 금액을 넘으면 거절하는지 검사합니다.
	 */
	@Test
	@DisplayName("달러 매수 가능 금액이 부족한 주문을 거절한다")
	void 달러_매수_가능_금액이_부족한_주문을_거절한다() {
		buyingPowerClient.response = new BuyingPowerResponse(
				ACCOUNT_SEQ, "USD", new BigDecimal("100"));

		assertThatThrownBy(() -> previewService.createPreview(
				new AmountOrderPreviewRequest(ACCOUNT_SEQ, "AAPL", new BigDecimal("100"))))
				.isInstanceOf(AmountOrderPreviewException.class)
				.hasMessage("달러 매수 가능 금액이 부족합니다.");
		assertThat(exchangeRateClient.callCount).isZero();
		assertThat(previewStore.previews).isEmpty();
	}

	/**
	 * 조회 결과의 계좌가 요청 계좌와 다르면 다른 계좌의 금융값을 사용하지 않는지 검사합니다.
	 */
	@Test
	@DisplayName("요청과 다른 계좌의 수수료와 매수 가능 금액을 거절한다")
	void 요청과_다른_계좌의_수수료와_매수_가능_금액을_거절한다() {
		commissionsClient.response = new CommissionsResponse(
				2L,
				List.of(new CommissionItem("US", new BigDecimal("0.001"), null, null)));

		assertThatThrownBy(() -> previewService.createPreview(
				new AmountOrderPreviewRequest(ACCOUNT_SEQ, "AAPL", new BigDecimal("100"))))
				.isInstanceOf(AmountOrderPreviewException.class)
				.hasMessage("미국 시장의 매매 수수료를 찾지 못했습니다.");

		commissionsClient.response = 수수료_응답을_만든다("0.001");
		buyingPowerClient.response = new BuyingPowerResponse(
				2L, "USD", new BigDecimal("1000"));

		assertThatThrownBy(() -> previewService.createPreview(
				new AmountOrderPreviewRequest(ACCOUNT_SEQ, "AAPL", new BigDecimal("100"))))
				.isInstanceOf(AmountOrderPreviewException.class)
				.hasMessage("달러 매수 가능 금액 응답이 올바르지 않습니다.");
		assertThat(previewStore.previews).isEmpty();
	}

	/**
	 * 국내 종목의 원화 현재가는 금액 주문 계산에 사용하지 않는지 검사합니다.
	 */
	@Test
	@DisplayName("국내 종목 현재가를 미국 금액 주문에서 거절한다")
	void 국내_종목_현재가를_미국_금액_주문에서_거절한다() {
		priceClient.response = new StockPriceResponse(
				"SAMSUNG", new BigDecimal("70000"), "KRW",
				OffsetDateTime.parse("2026-09-08T09:30:00+09:00"));

		assertThatThrownBy(() -> previewService.createPreview(
				new AmountOrderPreviewRequest(ACCOUNT_SEQ, "SAMSUNG", new BigDecimal("100"))))
				.isInstanceOf(AmountOrderPreviewException.class)
				.hasMessage("금액 주문은 미국 주식의 올바른 달러 현재가가 필요합니다.");
		assertThat(commissionsClient.callCount).isZero();
	}

	/**
	 * 미리보기 생성 시각에 만료된 환율은 고액 주문 판단에 사용하지 않는지 검사합니다.
	 */
	@Test
	@DisplayName("만료된 달러 원화 환율을 거절한다")
	void 만료된_달러_원화_환율을_거절한다() {
		exchangeRateClient.response = 환율_응답을_만든다(
				"1400", "2026-09-08T09:30:30+09:00");

		assertThatThrownBy(() -> previewService.createPreview(
				new AmountOrderPreviewRequest(ACCOUNT_SEQ, "AAPL", new BigDecimal("100"))))
				.isInstanceOf(AmountOrderPreviewException.class)
				.hasMessage("현재 유효한 달러 원화 참고 환율이 필요합니다.");
		assertThat(previewStore.previews).isEmpty();
	}

	/**
	 * 잘못된 계좌·종목·금액은 외부 조회 전에 차단하는지 검사합니다.
	 */
	@Test
	@DisplayName("잘못된 금액 주문 미리보기 요청을 외부 조회 전에 거절한다")
	void 잘못된_금액_주문_미리보기_요청을_외부_조회_전에_거절한다() {
		assertThatThrownBy(() -> previewService.createPreview(
				new AmountOrderPreviewRequest(0L, "AAPL", BigDecimal.ONE)))
				.isInstanceOf(AmountOrderPreviewException.class)
				.hasMessage("계좌 식별값은 1 이상이어야 합니다.");
		assertThatThrownBy(() -> previewService.createPreview(
				new AmountOrderPreviewRequest(ACCOUNT_SEQ, "005930", BigDecimal.ONE)))
				.isInstanceOf(AmountOrderPreviewException.class)
				.hasMessage("금액 주문에는 올바른 미국 주식 종목 코드가 필요합니다.");
		assertThatThrownBy(() -> previewService.createPreview(
				new AmountOrderPreviewRequest(ACCOUNT_SEQ, "AAPL", BigDecimal.ZERO)))
				.isInstanceOf(AmountOrderPreviewException.class)
				.hasMessage("달러 주문 금액은 0보다 커야 합니다.");
		assertThat(priceClient.callCount).isZero();
	}

	/**
	 * 저장된 미리보기는 식별값으로 같은 계산 결과를 다시 조회하는지 검사합니다.
	 */
	@Test
	@DisplayName("저장된 금액 주문 미리보기를 식별값으로 조회한다")
	void 저장된_금액_주문_미리보기를_식별값으로_조회한다() {
		AmountOrderPreviewResponse created = previewService.createPreview(
				new AmountOrderPreviewRequest(ACCOUNT_SEQ, "AAPL", new BigDecimal("100")));

		AmountOrderPreviewResponse found = previewService.getPreview(created.previewId());

		assertThat(found).isEqualTo(created);
		assertThatThrownBy(() -> previewService.getPreview(UUID.randomUUID().toString()))
				.isInstanceOf(AmountOrderPreviewNotFoundException.class)
				.hasMessage("금액 주문 미리보기를 찾을 수 없습니다.");
	}

	/**
	 * 승인 가능한 미리보기는 금융 계산값을 바꾸지 않고 상태와 승인 시각만 기록하는지 검사합니다.
	 */
	@Test
	@DisplayName("유효한 금액 주문 미리보기를 한 번 승인한다")
	void 유효한_금액_주문_미리보기를_한_번_승인한다() {
		AmountOrderPreviewResponse pending = previewService.createPreview(
				new AmountOrderPreviewRequest(ACCOUNT_SEQ, "AAPL", new BigDecimal("100")));

		AmountOrderPreviewResponse approved = previewService.approvePreview(pending.previewId());

		assertThat(approved.status()).isEqualTo(OrderPreviewStatus.APPROVED);
		assertThat(approved.approvedAt()).isEqualTo(pending.createdAt());
		assertThat(approved.previewId()).isEqualTo(pending.previewId());
		assertThat(approved.accountSeq()).isEqualTo(pending.accountSeq());
		assertThat(approved.symbol()).isEqualTo(pending.symbol());
		assertThat(approved.orderAmount()).isEqualByComparingTo(pending.orderAmount());
		assertThat(approved.estimatedTotalCost())
				.isEqualByComparingTo(pending.estimatedTotalCost());
		assertThat(approved.exchangeRate()).isEqualByComparingTo(pending.exchangeRate());
	}

	/**
	 * 이미 승인한 미리보기를 다시 승인하려 하면 중복 승인으로 거절하는지 검사합니다.
	 */
	@Test
	@DisplayName("이미 승인한 금액 주문 미리보기의 중복 승인을 거절한다")
	void 이미_승인한_금액_주문_미리보기의_중복_승인을_거절한다() {
		AmountOrderPreviewResponse pending = previewService.createPreview(
				new AmountOrderPreviewRequest(ACCOUNT_SEQ, "AAPL", new BigDecimal("100")));
		previewService.approvePreview(pending.previewId());

		assertThatThrownBy(() -> previewService.approvePreview(pending.previewId()))
				.isInstanceOf(AmountOrderPreviewStateException.class)
				.hasMessage("이미 승인한 금액 주문 미리보기입니다.");
	}

	/**
	 * 승인 유효시간과 정확히 같거나 더 늦은 시각에는 승인하지 않고 만료시키는지 검사합니다.
	 */
	@Test
	@DisplayName("승인 시간이 지난 금액 주문 미리보기를 만료시킨다")
	void 승인_시간이_지난_금액_주문_미리보기를_만료시킨다() {
		AmountOrderPreviewResponse pending = previewService.createPreview(
				new AmountOrderPreviewRequest(ACCOUNT_SEQ, "AAPL", new BigDecimal("100")));
		previewService = 미리보기_서비스를_현재_시각으로_다시_만든다(
				FIXED_INSTANT.plus(Duration.ofMinutes(2)));

		assertThatThrownBy(() -> previewService.approvePreview(pending.previewId()))
				.isInstanceOf(AmountOrderPreviewExpiredException.class)
				.hasMessage("금액 주문 미리보기의 승인 시간이 지났습니다. 새 미리보기를 만들어 주세요.");
		assertThat(previewStore.findById(pending.previewId()))
				.get()
				.extracting(AmountOrderPreviewResponse::status)
				.isEqualTo(OrderPreviewStatus.EXPIRED);
	}

	/**
	 * 잘못된 승인 식별값은 저장소 상태를 바꾸기 전에 요청 오류로 거절하는지 검사합니다.
	 */
	@Test
	@DisplayName("형식이 잘못된 금액 주문 미리보기 승인 식별값을 거절한다")
	void 형식이_잘못된_금액_주문_미리보기_승인_식별값을_거절한다() {
		assertThatThrownBy(() -> previewService.approvePreview("not-a-uuid"))
				.isInstanceOf(AmountOrderPreviewException.class)
				.hasMessage("금액 주문 미리보기 식별값 형식이 올바르지 않습니다.");
	}

	/**
	 * 요청 문자열 표현에서 계좌와 주문 금액을 숨기는지 검사합니다.
	 */
	@Test
	@DisplayName("금액 주문 미리보기 요청 문자열에서 금융값을 숨긴다")
	void 금액_주문_미리보기_요청_문자열에서_금융값을_숨긴다() {
		AmountOrderPreviewRequest request = new AmountOrderPreviewRequest(
				123456L, "AAPL", new BigDecimal("98765.43"));

		assertThat(request.toString())
				.doesNotContain("123456", "98765.43")
				.contains("accountSeq=***", "orderAmount=***");
	}

	/**
	 * 대부분의 테스트가 사용할 정상 현재가·수수료·잔고·환율 응답을 준비합니다.
	 */
	private void 정상_조회_결과를_준비한다() {
		priceClient.response = new StockPriceResponse(
				"AAPL", new BigDecimal("200"), "USD",
				OffsetDateTime.parse("2026-09-08T09:30:00+09:00"));
		commissionsClient.response = 수수료_응답을_만든다("0.001");
		buyingPowerClient.response = new BuyingPowerResponse(
				ACCOUNT_SEQ, "USD", new BigDecimal("1000000"));
		exchangeRateClient.response = 환율_응답을_만든다(
				"1400", "2026-09-08T09:31:00+09:00");
	}

	/**
	 * 지정한 수수료율로 미국 시장 수수료 응답을 만듭니다.
	 *
	 * @param commissionRate 미국 시장 수수료율
	 * @return 미국 시장 항목이 포함된 계좌 수수료 응답
	 */
	private CommissionsResponse 수수료_응답을_만든다(String commissionRate) {
		return new CommissionsResponse(
				ACCOUNT_SEQ,
				List.of(new CommissionItem(
						"US", new BigDecimal(commissionRate), LocalDate.parse("2026-01-01"), null)));
	}

	/**
	 * 지정한 매수 환율과 종료 시각으로 USD→KRW 참고 환율 응답을 만듭니다.
	 *
	 * @param rate 달러 원화 매수 환율
	 * @param validUntil 환율 유효 종료 시각
	 * @return 미리보기 계산에 사용할 참고 환율
	 */
	private ExchangeRateResponse 환율_응답을_만든다(String rate, String validUntil) {
		return new ExchangeRateResponse(
				"USD",
				"KRW",
				new BigDecimal(rate),
				new BigDecimal(rate),
				BigDecimal.ZERO,
				ExchangeRateChangeType.EQUAL,
				OffsetDateTime.parse("2026-09-08T09:30:00+09:00"),
				OffsetDateTime.parse(validUntil));
	}

	/**
	 * 같은 가짜 조회 기능과 저장소를 유지하면서 승인 판단에 사용할 현재 시각만 바꿉니다.
	 *
	 * @param instant 새로 적용할 현재 시각
	 * @return 변경된 고정 시각을 사용하는 금액 주문 미리보기 서비스
	 */
	private AmountOrderPreviewService 미리보기_서비스를_현재_시각으로_다시_만든다(Instant instant) {
		return new AmountOrderPreviewService(
				priceClient,
				buyingPowerClient,
				commissionsClient,
				exchangeRateClient,
				previewStore,
				new OrderPreviewProperties(Duration.ofMinutes(2)),
				Clock.fixed(instant, ZoneOffset.UTC));
	}

	/**
	 * 미리보기 저장과 식별값 조회만 제공하는 테스트 전용 메모리 저장소입니다.
	 */
	private static final class MemoryAmountOrderPreviewStore implements AmountOrderPreviewStore {

		private final Map<String, AmountOrderPreviewResponse> previews = new HashMap<>();

		/** 검증을 마친 미리보기를 메모리에 저장합니다. */
		@Override
		public AmountOrderPreviewResponse save(AmountOrderPreviewResponse preview) {
			previews.put(preview.previewId(), preview);
			return preview;
		}

		/** 아직 유효한 승인 대기 미리보기만 승인 상태로 변경합니다. */
		@Override
		public boolean approvePending(String previewId, OffsetDateTime approvedAt) {
			AmountOrderPreviewResponse preview = previews.get(previewId);
			if (preview == null
					|| preview.status() != OrderPreviewStatus.PENDING_APPROVAL
					|| !preview.expiresAt().isAfter(approvedAt)) {
				return false;
			}
			previews.put(previewId, 상태를_바꾼다(
					preview, OrderPreviewStatus.APPROVED, approvedAt));
			return true;
		}

		/** 유효시간이 지난 승인 대기 미리보기만 만료 상태로 변경합니다. */
		@Override
		public boolean expirePending(String previewId, OffsetDateTime now) {
			AmountOrderPreviewResponse preview = previews.get(previewId);
			if (preview == null
					|| preview.status() != OrderPreviewStatus.PENDING_APPROVAL
					|| preview.expiresAt().isAfter(now)) {
				return false;
			}
			previews.put(previewId, 상태를_바꾼다(
					preview, OrderPreviewStatus.EXPIRED, null));
			return true;
		}

		/** 유효한 승인 미리보기만 사용 완료 상태로 변경합니다. */
		@Override
		public boolean consumeApproved(String previewId, OffsetDateTime consumedAt) {
			AmountOrderPreviewResponse preview = previews.get(previewId);
			if (preview == null
					|| preview.status() != OrderPreviewStatus.APPROVED
					|| !preview.expiresAt().isAfter(consumedAt)) {
				return false;
			}
			previews.put(previewId, 상태를_바꾼다(
					preview, OrderPreviewStatus.CONSUMED, preview.approvedAt()));
			return true;
		}

		/**
		 * 기존 금융 계산값을 유지하면서 상태와 승인 시각만 바꾼 새 응답을 만듭니다.
		 *
		 * @param preview 변경 전 미리보기
		 * @param status 변경할 상태
		 * @param approvedAt 승인 시각이며 만료 상태이면 null
		 * @return 상태만 변경된 미리보기
		 */
		private AmountOrderPreviewResponse 상태를_바꾼다(
				AmountOrderPreviewResponse preview,
				OrderPreviewStatus status,
				OffsetDateTime approvedAt) {
			return new AmountOrderPreviewResponse(
					preview.previewId(),
					preview.createdAt(),
					preview.expiresAt(),
					preview.accountSeq(),
					preview.symbol(),
					preview.side(),
					preview.orderType(),
					preview.orderAmount(),
					preview.currency(),
					preview.marketCountry(),
					preview.referencePrice(),
					preview.estimatedQuantity(),
					preview.commissionRate(),
					preview.estimatedCommission(),
					preview.estimatedTotalCost(),
					preview.exchangeRate(),
					preview.exchangeRateValidFrom(),
					preview.exchangeRateValidUntil(),
					preview.estimatedOrderAmountKrw(),
					preview.requiresHighValueConfirmation(),
					preview.orderReady(),
					status,
					approvedAt);
		}

		/** 식별값에 해당하는 메모리 미리보기를 반환합니다. */
		@Override
		public Optional<AmountOrderPreviewResponse> findById(String previewId) {
			return Optional.ofNullable(previews.get(previewId));
		}
	}

	/**
	 * 준비된 현재가를 반환하고 호출 횟수를 기록하는 테스트 전용 클라이언트입니다.
	 */
	private static final class FixedPriceClient extends TossPriceClient {

		private StockPriceResponse response;
		private int callCount;

		/** 실제 REST 구성 없이 테스트용 부모 객체를 초기화합니다. */
		private FixedPriceClient() {
			super(null, null);
		}

		/** 요청 횟수를 기록하고 준비된 현재가를 반환합니다. */
		@Override
		public StockPriceResponse getCurrentPrice(String symbol) {
			callCount++;
			return response;
		}
	}

	/**
	 * 준비된 달러 매수 가능 금액을 반환하는 테스트 전용 클라이언트입니다.
	 */
	private static final class FixedBuyingPowerClient extends TossBuyingPowerClient {

		private BuyingPowerResponse response;
		private String requestedCurrency;

		/** 실제 REST 구성 없이 테스트용 부모 객체를 초기화합니다. */
		private FixedBuyingPowerClient() {
			super(null, null);
		}

		/** 요청 통화를 기록하고 준비된 매수 가능 금액을 반환합니다. */
		@Override
		public BuyingPowerResponse getBuyingPower(long accountSeq, String currency) {
			requestedCurrency = currency;
			return response;
		}
	}

	/**
	 * 준비된 미국 시장 수수료를 반환하고 호출 횟수를 기록하는 테스트 전용 클라이언트입니다.
	 */
	private static final class FixedCommissionsClient extends TossCommissionsClient {

		private CommissionsResponse response;
		private int callCount;

		/** 실제 REST 구성 없이 테스트용 부모 객체를 초기화합니다. */
		private FixedCommissionsClient() {
			super(null, null);
		}

		/** 요청 횟수를 기록하고 준비된 수수료 목록을 반환합니다. */
		@Override
		public CommissionsResponse getCommissions(long accountSeq) {
			callCount++;
			return response;
		}
	}

	/**
	 * 준비된 USD→KRW 환율을 반환하고 요청 인수를 기록하는 테스트 전용 클라이언트입니다.
	 */
	private static final class FixedExchangeRateClient extends TossExchangeRateClient {

		private ExchangeRateResponse response;
		private int callCount;
		private String requestedBaseCurrency;
		private String requestedQuoteCurrency;

		/** 실제 REST 구성 없이 테스트용 부모 객체를 초기화합니다. */
		private FixedExchangeRateClient() {
			super(null, null);
		}

		/** 요청 인수를 기록하고 준비된 환율을 반환합니다. */
		@Override
		public ExchangeRateResponse getExchangeRate(
				String baseCurrency,
				String quoteCurrency,
				OffsetDateTime dateTime) {
			callCount++;
			requestedBaseCurrency = baseCurrency;
			requestedQuoteCurrency = quoteCurrency;
			return response;
		}
	}
}
