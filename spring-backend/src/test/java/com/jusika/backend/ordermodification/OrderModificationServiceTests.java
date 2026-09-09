package com.jusika.backend.ordermodification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.jusika.backend.brokersafety.BrokerMutationBlockedException;
import com.jusika.backend.order.OrderModificationSubmissionRequest;
import com.jusika.backend.order.OrderOperationResponse;
import com.jusika.backend.orderexecution.OrderExecutionConflictException;
import com.jusika.backend.orderexecution.OrderExecutionFailureType;
import com.jusika.backend.orderexecution.OrderExecutionStatus;
import com.jusika.backend.orderexecution.OrderExecutionSubmissionException;
import com.jusika.backend.orderexecution.OrderSubmissionException;
import com.jusika.backend.orderhistory.OrderDetailResponse;
import com.jusika.backend.orderhistory.OrderDetailResponse.ExecutionDetail;
import com.jusika.backend.orderhistory.OrderStatus;
import com.jusika.backend.orderpreview.OrderPreviewException;
import com.jusika.backend.orderpreview.OrderPreviewProperties;
import com.jusika.backend.orderpreview.OrderSide;
import com.jusika.backend.orderpreview.OrderType;
import com.jusika.backend.stock.StockPriceResponse;
import com.jusika.backend.toss.market.TossPriceClient;
import com.jusika.backend.toss.orderhistory.TossOrderHistoryClient;

/** 실제 증권사를 호출하지 않고 주문 정정의 시장별 규칙과 안전 실행을 검사합니다. */
class OrderModificationServiceTests {
	private static final long ACCOUNT_SEQ = 1L;
	private static final String ORDER_ID = "original-order-id";
	private static final OffsetDateTime NOW = OffsetDateTime.of(
			2026, 9, 6, 12, 0, 0, 0, ZoneOffset.UTC);

	private RecordingHistoryClient historyClient;
	private RecordingPriceClient priceClient;
	private MemoryPreviewStore previewStore;
	private MemoryExecutionStore executionStore;
	private RecordingModificationGateway gateway;
	private OrderModificationService service;

	/** 각 테스트가 사용할 고정 시계와 메모리 가짜 객체를 준비합니다. */
	@BeforeEach
	void 각_테스트에_필요한_정정_서비스를_준비한다() {
		historyClient = new RecordingHistoryClient();
		priceClient = new RecordingPriceClient();
		previewStore = new MemoryPreviewStore();
		executionStore = new MemoryExecutionStore();
		gateway = new RecordingModificationGateway();
		Clock clock = Clock.fixed(Instant.parse("2026-09-06T12:00:00Z"), ZoneOffset.UTC);
		service = new OrderModificationService(historyClient, priceClient, previewStore,
				executionStore, gateway, new OrderPreviewProperties(Duration.ofMinutes(2)), clock);
	}

	/** 국내 지정가의 수량·가격과 예상 주문금액을 미리보기에 고정하는지 검사합니다. */
	@Test
	@DisplayName("국내 지정가 정정 미리보기를 만든다")
	void 국내_지정가_정정_미리보기를_만든다() {
		historyClient.response = 주문을_만든다(
				"KRW", OrderStatus.PENDING, "LIMIT", "10", "0", "70000");

		OrderModificationPreviewResponse preview = service.createPreview(
				new OrderModificationPreviewRequest(
						ACCOUNT_SEQ, ORDER_ID, OrderType.LIMIT,
						new BigDecimal("15"), new BigDecimal("71000")));

		assertThat(preview.requestedQuantity()).isEqualByComparingTo("15");
		assertThat(preview.requestedPrice()).isEqualByComparingTo("71000");
		assertThat(preview.estimatedOrderAmount()).isEqualByComparingTo("1065000");
		assertThat(preview.referencePrice()).isNull();
		assertThat(preview.status()).isEqualTo(OrderModificationPreviewStatus.PENDING_APPROVAL);
		assertThat(priceClient.callCount).isZero();
		assertThat(gateway.callCount).isZero();
	}

	/** 미국 주문에 수량을 넣으면 외부 정정 호출 전에 차단하는지 검사합니다. */
	@Test
	@DisplayName("미국 주식 수량 정정은 미리보기에서 차단한다")
	void 미국_주식_수량_정정은_미리보기에서_차단한다() {
		historyClient.response = 주문을_만든다(
				"USD", OrderStatus.PENDING, "LIMIT", "10", "0", "185");

		assertThatThrownBy(() -> service.createPreview(new OrderModificationPreviewRequest(
				ACCOUNT_SEQ, ORDER_ID, OrderType.LIMIT, BigDecimal.ONE,
				new BigDecimal("186"))))
				.isInstanceOf(OrderPreviewException.class)
				.hasMessage("미국 주식 주문 정정은 가격만 변경할 수 있습니다.");
		assertThat(previewStore.values).isEmpty();
		assertThat(gateway.callCount).isZero();
	}

	/** 국내 시장가 정정은 현재가로 금액을 계산하고 고액 확인 필요를 표시하는지 검사합니다. */
	@Test
	@DisplayName("시장가 정정의 현재 예상금액이 1억원 이상이면 확인을 요구한다")
	void 시장가_정정의_현재_예상금액이_1억원_이상이면_확인을_요구한다() {
		historyClient.response = 주문을_만든다(
				"KRW", OrderStatus.PENDING, "LIMIT", "2000", "0", "50000");
		priceClient.response = new StockPriceResponse(
				"005930", new BigDecimal("60000"), "KRW", NOW);

		OrderModificationPreviewResponse preview = service.createPreview(new OrderModificationPreviewRequest(
				ACCOUNT_SEQ, ORDER_ID, OrderType.MARKET,
				new BigDecimal("2000"), null));

		assertThat(preview.requiresHighValueConfirmation()).isTrue();
		assertThat(preview.estimatedOrderAmount()).isEqualByComparingTo("120000000");
		assertThat(priceClient.callCount).isEqualTo(1);
	}

	/** 승인된 정정을 재검증하고 새 주문번호와 함께 접수 상태로 저장하는지 검사합니다. */
	@Test
	@DisplayName("승인된 정정을 모의 실행하고 새 주문번호를 저장한다")
	void 승인된_정정을_모의_실행하고_새_주문번호를_저장한다() {
		historyClient.response = 주문을_만든다(
				"KRW", OrderStatus.PENDING, "LIMIT", "10", "0", "70000");
		OrderModificationPreviewResponse preview = 승인된_미리보기를_저장한다();

		OrderModificationExecutionResponse result = service.executeApprovedPreview(preview.previewId());

		assertThat(result.status()).isEqualTo(OrderExecutionStatus.ACCEPTED);
		assertThat(result.operationOrderId()).isEqualTo("new-order-id");
		assertThat(gateway.callCount).isEqualTo(1);
		assertThat(previewStore.findById(preview.previewId()).orElseThrow().status())
				.isEqualTo(OrderModificationPreviewStatus.CONSUMED);
	}

	/** 승인 후 원주문 가격이 바뀌면 실행권을 만들기 전에 차단하는지 검사합니다. */
	@Test
	@DisplayName("승인 뒤 원주문이 바뀌면 정정 실행을 차단한다")
	void 승인_뒤_원주문이_바뀌면_정정_실행을_차단한다() {
		OrderModificationPreviewResponse preview = 승인된_미리보기를_저장한다();
		historyClient.response = 주문을_만든다(
				"KRW", OrderStatus.PENDING, "LIMIT", "10", "0", "70500");

		assertThatThrownBy(() -> service.executeApprovedPreview(preview.previewId()))
				.isInstanceOf(OrderExecutionConflictException.class)
				.hasMessageContaining("원주문 내용이 변경");
		assertThat(executionStore.values).isEmpty();
		assertThat(gateway.callCount).isZero();
	}

	/** LIVE 가용성 검사가 실패하면 외부 조회나 미리보기·실행 상태를 변경하지 않는지 검사합니다. */
	@Test
	@DisplayName("LIVE 안전정책 차단은 주문 정정 재조회와 미리보기 소비 전에 적용된다")
	void LIVE_안전정책_차단은_주문_정정_재조회와_미리보기_소비_전에_적용된다() {
		OrderModificationPreviewResponse preview = 승인된_미리보기를_저장한다();
		gateway.availabilityFailure = new BrokerMutationBlockedException(
				"테스트 LIVE 주문 정정 차단");

		assertThatThrownBy(() -> service.executeApprovedPreview(preview.previewId()))
				.isInstanceOf(BrokerMutationBlockedException.class)
				.hasMessage("테스트 LIVE 주문 정정 차단");
		assertThat(previewStore.findById(preview.previewId()).orElseThrow().status())
				.isEqualTo(OrderModificationPreviewStatus.APPROVED);
		assertThat(executionStore.values).isEmpty();
		assertThat(historyClient.callCount).isZero();
		assertThat(priceClient.callCount).isZero();
		assertThat(gateway.callCount).isZero();
	}

	/** 정정 결과가 불명확하면 UNKNOWN으로 기록하고 자동 재시도를 하지 않는지 검사합니다. */
	@Test
	@DisplayName("정정 결과 불명은 UNKNOWN으로 저장하고 자동 재시도하지 않는다")
	void 정정_결과_불명은_UNKNOWN으로_저장하고_자동_재시도하지_않는다() {
		historyClient.response = 주문을_만든다(
				"KRW", OrderStatus.PENDING, "LIMIT", "10", "0", "70000");
		OrderModificationPreviewResponse preview = 승인된_미리보기를_저장한다();
		gateway.unknown = true;

		assertThatThrownBy(() -> service.executeApprovedPreview(preview.previewId()))
				.isInstanceOf(OrderExecutionSubmissionException.class)
				.hasMessageContaining("자동으로 다시 정정하지 마세요");
		OrderModificationExecutionResponse stored = executionStore.values.values().iterator().next();
		assertThat(stored.status()).isEqualTo(OrderExecutionStatus.UNKNOWN);
		assertThat(stored.failureType()).isEqualTo(OrderExecutionFailureType.SUBMISSION_UNKNOWN);
		assertThat(gateway.callCount).isEqualTo(1);
	}

	/** 실행 테스트에 사용할 승인된 국내 지정가 정정 사본을 메모리에 저장합니다. */
	private OrderModificationPreviewResponse 승인된_미리보기를_저장한다() {
		return previewStore.save(new OrderModificationPreviewResponse(
				"00000000-0000-0000-0000-000000000001", NOW.minusSeconds(10), NOW.plusMinutes(1),
				ACCOUNT_SEQ, ORDER_ID, "005930", OrderSide.BUY, "LIMIT", "DAY",
				new BigDecimal("70000"), new BigDecimal("10"), null, BigDecimal.ZERO,
				"KRW", OrderType.LIMIT, new BigDecimal("15"), new BigDecimal("71000"),
				null, new BigDecimal("1065000"), false,
				OrderModificationPreviewStatus.APPROVED, NOW.minusSeconds(5)));
	}

	/** 통화·상태·원주문 값을 지정해 테스트용 주문 상세를 만듭니다. */
	private OrderDetailResponse 주문을_만든다(
			String currency, OrderStatus status, String type,
			String quantity, String filled, String price) {
		return new OrderDetailResponse(
				ACCOUNT_SEQ, ORDER_ID, "005930", OrderSide.BUY, type, "DAY", status,
				"BROKER_STATUS", new BigDecimal(price), new BigDecimal(quantity), null,
				currency, NOW.minusMinutes(1), null,
				new ExecutionDetail(new BigDecimal(filled), null, null, null, null, null, null));
	}

	/** 준비한 주문 상세를 반환하고 읽기 호출 횟수를 기록합니다. */
	private static final class RecordingHistoryClient extends TossOrderHistoryClient {
		private OrderDetailResponse response;
		private int callCount;
		/** 실제 REST 연결 없이 부모 객체를 초기화합니다. */
		private RecordingHistoryClient() { super(null, null); }
		/** 주문 상세 조회 횟수를 기록하고 준비된 응답을 반환합니다. */
		@Override public OrderDetailResponse getOrder(long accountSeq, String orderId) {
			callCount++;
			return response;
		}
	}

	/** 준비한 현재가를 반환하고 조회 횟수를 기록합니다. */
	private static final class RecordingPriceClient extends TossPriceClient {
		private StockPriceResponse response;
		private int callCount;
		/** 실제 REST 연결 없이 부모 객체를 초기화합니다. */
		private RecordingPriceClient() { super(null, null); }
		/** 현재가 조회 횟수를 기록하고 준비된 응답을 반환합니다. */
		@Override public StockPriceResponse getCurrentPrice(String symbol) {
			callCount++;
			return response;
		}
	}

	/** 정정 미리보기 상태 변경을 메모리에서 재현합니다. */
	private static final class MemoryPreviewStore implements OrderModificationPreviewStore {
		private final Map<String, OrderModificationPreviewResponse> values = new HashMap<>();
		/** 새 미리보기를 저장합니다. */
		@Override public OrderModificationPreviewResponse save(OrderModificationPreviewResponse preview) {
			values.put(preview.previewId(), preview);
			return preview;
		}
		/** 이 서비스 테스트에서는 승인 상태 사본을 직접 저장하므로 상태를 바꾸지 않습니다. */
		@Override public boolean approvePending(String id, OffsetDateTime at) { return false; }
		/** 이 서비스 테스트에서는 만료 시각을 직접 정하므로 상태를 바꾸지 않습니다. */
		@Override public boolean expirePending(String id, OffsetDateTime now) { return false; }
		/** 승인된 미리보기를 사용 상태로 변경합니다. */
		@Override public boolean consumeApproved(String id, OffsetDateTime at) {
			OrderModificationPreviewResponse preview = values.get(id);
			if (preview == null || preview.status() != OrderModificationPreviewStatus.APPROVED) return false;
			values.put(id, 상태를_바꾼다(preview, OrderModificationPreviewStatus.CONSUMED));
			return true;
		}
		/** 식별값으로 미리보기를 조회합니다. */
		@Override public Optional<OrderModificationPreviewResponse> findById(String id) {
			return Optional.ofNullable(values.get(id));
		}
		/** 기존 주문값을 유지하면서 미리보기 상태만 변경합니다. */
		private OrderModificationPreviewResponse 상태를_바꾼다(
				OrderModificationPreviewResponse preview, OrderModificationPreviewStatus status) {
			return new OrderModificationPreviewResponse(
					preview.previewId(), preview.createdAt(), preview.expiresAt(),
					preview.accountSeq(), preview.originalOrderId(), preview.symbol(), preview.side(),
					preview.originalOrderTypeCode(), preview.originalTimeInForceCode(),
					preview.originalPrice(), preview.originalQuantity(), preview.originalOrderAmount(),
					preview.filledQuantity(), preview.currency(), preview.requestedOrderType(),
					preview.requestedQuantity(), preview.requestedPrice(), preview.referencePrice(),
					preview.estimatedOrderAmount(), preview.requiresHighValueConfirmation(), status,
					preview.approvedAt());
		}
	}

	/** 정정 실행의 중복 방지와 상태 변경을 메모리에서 재현합니다. */
	private static final class MemoryExecutionStore implements OrderModificationExecutionStore {
		private final Map<String, OrderModificationExecutionResponse> values = new HashMap<>();
		/** 원주문당 첫 실행만 저장합니다. */
		@Override public boolean claim(OrderModificationExecutionResponse execution) {
			if (values.values().stream().anyMatch(value ->
					value.originalOrderId().equals(execution.originalOrderId()))) return false;
			values.put(execution.executionId(), execution);
			return true;
		}
		/** 실행을 제출 중으로 변경합니다. */
		@Override public boolean markSubmitting(String id, OffsetDateTime at) {
			return 변경한다(id, OrderExecutionStatus.SUBMITTING, null, null, at, null);
		}
		/** 실행을 내부 오류로 종료합니다. */
		@Override public boolean markPreparationFailed(String id, OffsetDateTime at) {
			return 변경한다(id, OrderExecutionStatus.REJECTED,
					OrderExecutionFailureType.INTERNAL_STATE, null, null, at);
		}
		/** 실행을 새 주문번호와 함께 접수 상태로 변경합니다. */
		@Override public boolean markAccepted(String id, String operationOrderId, OffsetDateTime at) {
			return 변경한다(id, OrderExecutionStatus.ACCEPTED, null, operationOrderId, null, at);
		}
		/** 실행을 증권사 거절로 종료합니다. */
		@Override public boolean markRejected(String id, OffsetDateTime at) {
			return 변경한다(id, OrderExecutionStatus.REJECTED,
					OrderExecutionFailureType.BROKER_REJECTED, null, null, at);
		}
		/** 실행을 결과 불명으로 변경합니다. */
		@Override public boolean markUnknown(String id, OffsetDateTime at) {
			return 변경한다(id, OrderExecutionStatus.UNKNOWN,
					OrderExecutionFailureType.SUBMISSION_UNKNOWN, null, null, null);
		}
		/** 실행 식별값으로 조회합니다. */
		@Override public Optional<OrderModificationExecutionResponse> findById(String id) {
			return Optional.ofNullable(values.get(id));
		}
		/** 미리보기 식별값으로 조회합니다. */
		@Override public Optional<OrderModificationExecutionResponse> findByPreviewId(String id) {
			return values.values().stream().filter(value -> value.previewId().equals(id)).findFirst();
		}
		/** 원주문 식별값으로 조회합니다. */
		@Override public Optional<OrderModificationExecutionResponse> findByOriginalOrderId(String id) {
			return values.values().stream()
					.filter(value -> value.originalOrderId().equals(id)).findFirst();
		}
		/** 저장된 식별값을 유지하면서 실행 상태와 결과만 변경합니다. */
		private boolean 변경한다(
				String id, OrderExecutionStatus status, OrderExecutionFailureType failure,
				String operationOrderId, OffsetDateTime submitted, OffsetDateTime completed) {
			OrderModificationExecutionResponse value = values.get(id);
			if (value == null) return false;
			OffsetDateTime updated = completed != null ? completed : submitted != null ? submitted : NOW;
			values.put(id, new OrderModificationExecutionResponse(
					value.executionId(), value.previewId(), value.originalOrderId(), operationOrderId,
					value.brokerMode(), status, failure, value.createdAt(), updated,
					submitted != null ? submitted : value.submittedAt(), completed));
			return true;
		}
	}

	/** 정정 호출을 기록하고 정상 또는 결과 불명 응답을 재현합니다. */
	private static final class RecordingModificationGateway implements OrderModificationGateway {
		private int callCount;
		private boolean unknown;
		private RuntimeException availabilityFailure;
		/** 준비한 LIVE 안전 차단을 재현하거나 MOCK 정정 사용 가능 상태를 유지합니다. */
		@Override public void requireModificationAvailable() {
			if (availabilityFailure != null) throw availabilityFailure;
		}
		/** 정정 호출을 기록하고 새 모의 주문번호 또는 결과 불명 오류를 반환합니다. */
		@Override public OrderOperationResponse modifyOrder(
				long accountSeq, String originalOrderId, OrderModificationSubmissionRequest request) {
			callCount++;
			if (unknown) throw new OrderSubmissionException("테스트 결과 불명", true);
			return new OrderOperationResponse("new-order-id");
		}
		/** 테스트 모의 모드 이름을 반환합니다. */
		@Override public String mode() { return "MOCK"; }
	}
}
