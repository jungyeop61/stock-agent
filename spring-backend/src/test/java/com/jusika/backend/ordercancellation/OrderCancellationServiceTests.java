package com.jusika.backend.ordercancellation;

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
import com.jusika.backend.toss.orderhistory.TossOrderHistoryClient;

/** 실제 증권사 호출 없이 주문 취소의 승인·재검증·중복 방지 규칙을 검사합니다. */
class OrderCancellationServiceTests {

	private static final long ACCOUNT_SEQ = 1L;
	private static final String ORDER_ID = "broker-order-id";
	private static final OffsetDateTime NOW = OffsetDateTime.of(
			2026, 9, 6, 12, 0, 0, 0, ZoneOffset.UTC);

	private RecordingHistoryClient historyClient;
	private MemoryPreviewStore previewStore;
	private MemoryExecutionStore executionStore;
	private RecordingCancellationGateway gateway;
	private OrderCancellationService service;

	/** 각 테스트가 사용할 고정 시계와 메모리 저장소를 준비합니다. */
	@BeforeEach
	void 각_테스트에_필요한_취소_서비스를_준비한다() {
		historyClient = new RecordingHistoryClient();
		previewStore = new MemoryPreviewStore();
		executionStore = new MemoryExecutionStore();
		gateway = new RecordingCancellationGateway();
		Clock clock = Clock.fixed(Instant.parse("2026-09-06T12:00:00Z"), ZoneOffset.UTC);
		service = new OrderCancellationService(historyClient, previewStore, executionStore,
				gateway, new OrderPreviewProperties(Duration.ofMinutes(2)), clock);
	}

	/** 일부 체결 주문의 미체결 수량을 정확히 계산해 고정하는지 검사합니다. */
	@Test
	@DisplayName("일부 체결 주문의 남은 수량으로 취소 미리보기를 만든다")
	void 일부_체결_주문의_남은_수량으로_취소_미리보기를_만든다() {
		historyClient.response = 주문을_만든다(OrderStatus.PARTIAL_FILLED,
				new BigDecimal("10"), new BigDecimal("3"), new BigDecimal("70000"));

		OrderCancellationPreviewResponse response = service.createPreview(
				new OrderCancellationPreviewRequest(ACCOUNT_SEQ, ORDER_ID));

		assertThat(response.filledQuantity()).isEqualByComparingTo("3");
		assertThat(response.remainingQuantity()).isEqualByComparingTo("7");
		assertThat(response.status()).isEqualTo(OrderCancellationPreviewStatus.PENDING_APPROVAL);
		assertThat(historyClient.callCount).isEqualTo(1);
		assertThat(gateway.callCount).isZero();
	}

	/** 전부 체결된 주문은 미리보기 저장과 취소 호출 전에 차단하는지 검사합니다. */
	@Test
	@DisplayName("전부 체결된 주문은 취소 미리보기를 만들지 않는다")
	void 전부_체결된_주문은_취소_미리보기를_만들지_않는다() {
		historyClient.response = 주문을_만든다(OrderStatus.FILLED,
				new BigDecimal("10"), new BigDecimal("10"), new BigDecimal("70000"));

		assertThatThrownBy(() -> service.createPreview(
				new OrderCancellationPreviewRequest(ACCOUNT_SEQ, ORDER_ID)))
				.isInstanceOf(OrderPreviewException.class)
				.hasMessage("현재 상태에서는 주문을 취소할 수 없습니다.");
		assertThat(previewStore.values).isEmpty();
		assertThat(gateway.callCount).isZero();
	}

	/** 승인 뒤 원주문 가격이 달라지면 실행권과 취소 호출을 만들지 않는지 검사합니다. */
	@Test
	@DisplayName("승인 뒤 주문 내용이 바뀌면 취소 실행을 차단한다")
	void 승인_뒤_주문_내용이_바뀌면_취소_실행을_차단한다() {
		historyClient.response = 주문을_만든다(OrderStatus.PENDING,
				new BigDecimal("10"), BigDecimal.ZERO, new BigDecimal("70000"));
		OrderCancellationPreviewResponse preview = service.createPreview(
				new OrderCancellationPreviewRequest(ACCOUNT_SEQ, ORDER_ID));
		service.approvePreview(preview.previewId());
		historyClient.response = 주문을_만든다(OrderStatus.PENDING,
				new BigDecimal("10"), BigDecimal.ZERO, new BigDecimal("71000"));

		assertThatThrownBy(() -> service.executeApprovedPreview(preview.previewId()))
				.isInstanceOf(OrderExecutionConflictException.class)
				.hasMessageContaining("주문 내용이 변경");
		assertThat(executionStore.values).isEmpty();
		assertThat(gateway.callCount).isZero();
	}

	/** LIVE 가용성 검사가 실패하면 외부 조회나 미리보기·실행 상태를 변경하지 않는지 검사합니다. */
	@Test
	@DisplayName("LIVE 안전정책 차단은 주문 취소 재조회와 미리보기 소비 전에 적용된다")
	void LIVE_안전정책_차단은_주문_취소_재조회와_미리보기_소비_전에_적용된다() {
		historyClient.response = 주문을_만든다(OrderStatus.PENDING,
				new BigDecimal("10"), BigDecimal.ZERO, new BigDecimal("70000"));
		OrderCancellationPreviewResponse preview = 승인된_미리보기를_만든다();
		gateway.availabilityFailure = new BrokerMutationBlockedException(
				"테스트 LIVE 주문 취소 차단");
		int previewHistoryCalls = historyClient.callCount;

		assertThatThrownBy(() -> service.executeApprovedPreview(preview.previewId()))
				.isInstanceOf(BrokerMutationBlockedException.class)
				.hasMessage("테스트 LIVE 주문 취소 차단");
		assertThat(previewStore.findById(preview.previewId()).orElseThrow().status())
				.isEqualTo(OrderCancellationPreviewStatus.APPROVED);
		assertThat(executionStore.values).isEmpty();
		assertThat(historyClient.callCount).isEqualTo(previewHistoryCalls);
		assertThat(gateway.callCount).isZero();
	}

	/** 한 주문을 정상 취소한 뒤 다른 미리보기로 다시 취소하지 못하는지 검사합니다. */
	@Test
	@DisplayName("같은 원주문은 여러 미리보기로도 한 번만 취소한다")
	void 같은_원주문은_여러_미리보기로도_한_번만_취소한다() {
		historyClient.response = 주문을_만든다(OrderStatus.PENDING,
				new BigDecimal("10"), BigDecimal.ZERO, new BigDecimal("70000"));
		OrderCancellationPreviewResponse first = 승인된_미리보기를_만든다();
		OrderCancellationExecutionResponse accepted = service.executeApprovedPreview(first.previewId());
		OrderCancellationPreviewResponse second = 승인된_미리보기를_만든다();

		assertThatThrownBy(() -> service.executeApprovedPreview(second.previewId()))
				.isInstanceOf(OrderExecutionConflictException.class);
		assertThat(accepted.status()).isEqualTo(OrderExecutionStatus.ACCEPTED);
		assertThat(gateway.callCount).isEqualTo(1);
	}

	/** 취소 응답이 불명확하면 결과 불명으로 저장하고 자동 재호출하지 않는지 검사합니다. */
	@Test
	@DisplayName("취소 결과 불명은 UNKNOWN으로 저장하고 재시도하지 않는다")
	void 취소_결과_불명은_UNKNOWN으로_저장하고_재시도하지_않는다() {
		historyClient.response = 주문을_만든다(OrderStatus.PENDING,
				new BigDecimal("10"), BigDecimal.ZERO, new BigDecimal("70000"));
		OrderCancellationPreviewResponse preview = 승인된_미리보기를_만든다();
		gateway.unknown = true;

		assertThatThrownBy(() -> service.executeApprovedPreview(preview.previewId()))
				.isInstanceOf(OrderExecutionSubmissionException.class)
				.hasMessageContaining("자동으로 다시 취소하지 마세요");
		OrderCancellationExecutionResponse stored = executionStore.values.values().iterator().next();
		assertThat(stored.status()).isEqualTo(OrderExecutionStatus.UNKNOWN);
		assertThat(stored.failureType()).isEqualTo(OrderExecutionFailureType.SUBMISSION_UNKNOWN);
		assertThat(gateway.callCount).isEqualTo(1);
	}

	/** 생성과 승인을 연속 수행해 실행 가능한 취소 미리보기를 만듭니다. */
	private OrderCancellationPreviewResponse 승인된_미리보기를_만든다() {
		OrderCancellationPreviewResponse preview = service.createPreview(
				new OrderCancellationPreviewRequest(ACCOUNT_SEQ, ORDER_ID));
		return service.approvePreview(preview.previewId());
	}

	/** 테스트 조건에 맞는 국내 지정가 주문 상세를 만듭니다. */
	private OrderDetailResponse 주문을_만든다(
			OrderStatus status, BigDecimal quantity, BigDecimal filled, BigDecimal price) {
		return new OrderDetailResponse(
				ACCOUNT_SEQ, ORDER_ID, "005930", OrderSide.BUY, "LIMIT", "DAY", status,
				"BROKER_STATUS", price, quantity, null, "KRW", NOW.minusMinutes(1), null,
				new ExecutionDetail(filled, null, null, null, null, null, null));
	}

	/** 준비된 주문 상세를 반환하고 호출 횟수를 기록하는 읽기 전용 가짜 클라이언트입니다. */
	private static final class RecordingHistoryClient extends TossOrderHistoryClient {
		private OrderDetailResponse response;
		private int callCount;

		/** 실제 REST 연결 없이 부모 객체를 초기화합니다. */
		private RecordingHistoryClient() { super(null, null); }

		/** 조회 인수를 검사할 수 있도록 호출 횟수를 기록하고 준비된 주문을 반환합니다. */
		@Override
		public OrderDetailResponse getOrder(long accountSeq, String orderId) {
			callCount++;
			return response;
		}
	}

	/** 취소 미리보기의 상태 변화를 메모리에서 재현합니다. */
	private static final class MemoryPreviewStore implements OrderCancellationPreviewStore {
		private final Map<String, OrderCancellationPreviewResponse> values = new HashMap<>();

		/** 새 미리보기를 저장합니다. */
		@Override public OrderCancellationPreviewResponse save(OrderCancellationPreviewResponse preview) {
			values.put(preview.previewId(), preview); return preview;
		}
		/** 승인 대기 미리보기를 승인 상태로 변경합니다. */
		@Override public boolean approvePending(String id, OffsetDateTime at) {
			OrderCancellationPreviewResponse value = values.get(id);
			if (value == null || value.status() != OrderCancellationPreviewStatus.PENDING_APPROVAL
					|| !value.expiresAt().isAfter(at)) return false;
			values.put(id, 상태를_바꾼다(value, OrderCancellationPreviewStatus.APPROVED, at)); return true;
		}
		/** 만료된 승인 대기 미리보기를 만료 상태로 변경합니다. */
		@Override public boolean expirePending(String id, OffsetDateTime now) { return false; }
		/** 승인된 미리보기를 사용 상태로 변경합니다. */
		@Override public boolean consumeApproved(String id, OffsetDateTime at) {
			OrderCancellationPreviewResponse value = values.get(id);
			if (value == null || value.status() != OrderCancellationPreviewStatus.APPROVED) return false;
			values.put(id, 상태를_바꾼다(value, OrderCancellationPreviewStatus.CONSUMED, value.approvedAt())); return true;
		}
		/** 식별값으로 저장된 미리보기를 조회합니다. */
		@Override public Optional<OrderCancellationPreviewResponse> findById(String id) {
			return Optional.ofNullable(values.get(id));
		}
		/** 원래 금융값을 유지한 채 상태와 승인 시각만 바꿉니다. */
		private OrderCancellationPreviewResponse 상태를_바꾼다(OrderCancellationPreviewResponse p,
				OrderCancellationPreviewStatus status, OffsetDateTime approvedAt) {
			return new OrderCancellationPreviewResponse(p.previewId(), p.createdAt(), p.expiresAt(),
					p.accountSeq(), p.orderId(), p.symbol(), p.side(), p.orderTypeCode(),
					p.originalStatus(), p.price(), p.quantity(), p.filledQuantity(),
					p.remainingQuantity(), p.orderAmount(), p.currency(), status, approvedAt);
		}
	}

	/** 취소 실행의 중복 방지와 상태 변화를 메모리에서 재현합니다. */
	private static final class MemoryExecutionStore implements OrderCancellationExecutionStore {
		private final Map<String, OrderCancellationExecutionResponse> values = new HashMap<>();
		/** 원주문당 첫 실행만 저장합니다. */
		@Override public boolean claim(OrderCancellationExecutionResponse execution) {
			if (values.values().stream().anyMatch(value -> value.orderId().equals(execution.orderId()))) return false;
			values.put(execution.executionId(), execution); return true;
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
		/** 실행을 접수 상태로 변경합니다. */
		@Override public boolean markAccepted(String id, String operationOrderId, OffsetDateTime at) {
			return 변경한다(id, OrderExecutionStatus.ACCEPTED, null, operationOrderId, null, at);
		}
		/** 실행을 증권사 거절로 종료합니다. */
		@Override public boolean markRejected(String id, OffsetDateTime at) {
			return 변경한다(id, OrderExecutionStatus.REJECTED, OrderExecutionFailureType.BROKER_REJECTED, null, null, at);
		}
		/** 실행을 결과 불명 상태로 변경합니다. */
		@Override public boolean markUnknown(String id, OffsetDateTime at) {
			return 변경한다(id, OrderExecutionStatus.UNKNOWN, OrderExecutionFailureType.SUBMISSION_UNKNOWN, null, null, null);
		}
		/** 실행 식별값으로 조회합니다. */
		@Override public Optional<OrderCancellationExecutionResponse> findById(String id) { return Optional.ofNullable(values.get(id)); }
		/** 미리보기 식별값으로 조회합니다. */
		@Override public Optional<OrderCancellationExecutionResponse> findByPreviewId(String id) { return values.values().stream().filter(v -> v.previewId().equals(id)).findFirst(); }
		/** 원주문 식별값으로 조회합니다. */
		@Override public Optional<OrderCancellationExecutionResponse> findByOrderId(String id) { return values.values().stream().filter(v -> v.orderId().equals(id)).findFirst(); }
		/** 기존 식별값과 시각을 유지하며 상태 필드만 바꿉니다. */
		private boolean 변경한다(String id, OrderExecutionStatus status,
				OrderExecutionFailureType failure, String operationOrderId,
				OffsetDateTime submitted, OffsetDateTime completed) {
			OrderCancellationExecutionResponse value = values.get(id); if (value == null) return false;
			OffsetDateTime updated = completed != null ? completed : submitted != null ? submitted : NOW;
			values.put(id, new OrderCancellationExecutionResponse(value.executionId(), value.previewId(),
					value.orderId(), operationOrderId, value.brokerMode(), status, failure, value.createdAt(), updated,
					submitted != null ? submitted : value.submittedAt(), completed)); return true;
		}
	}

	/** 취소 호출 횟수를 기록하고 정상 또는 결과 불명 응답을 재현합니다. */
	private static final class RecordingCancellationGateway implements OrderCancellationGateway {
		private int callCount;
		private boolean unknown;
		private RuntimeException availabilityFailure;
		/** 준비한 LIVE 안전 차단을 재현하거나 MOCK 취소 사용 가능 상태를 유지합니다. */
		@Override public void requireCancellationAvailable() {
			if (availabilityFailure != null) throw availabilityFailure;
		}
		/** 취소 호출을 기록하고 설정된 결과를 반환합니다. */
		@Override public OrderOperationResponse cancelOrder(long accountSeq, String orderId) {
			callCount++;
			if (unknown) throw new OrderSubmissionException("테스트 결과 불명", true);
			return new OrderOperationResponse("new-" + orderId);
		}
		/** 테스트가 사용하는 모의 모드 이름을 반환합니다. */
		@Override public String mode() { return "MOCK"; }
	}
}
