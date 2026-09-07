package com.jusika.backend.conditionalordercancellation;

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
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.jusika.backend.conditionalorder.ConditionalOrderConditionStatus;
import com.jusika.backend.conditionalorder.ConditionalOrderConditionType;
import com.jusika.backend.conditionalorder.ConditionalOrderDetailResponse;
import com.jusika.backend.conditionalorder.ConditionalOrderDetailResponse.Condition;
import com.jusika.backend.conditionalorder.ConditionalOrderMarket;
import com.jusika.backend.conditionalorder.ConditionalOrderStatus;
import com.jusika.backend.conditionalorder.ConditionalOrderType;
import com.jusika.backend.orderexecution.OrderExecutionConflictException;
import com.jusika.backend.orderexecution.OrderExecutionFailureType;
import com.jusika.backend.orderexecution.OrderExecutionStatus;
import com.jusika.backend.orderexecution.OrderExecutionSubmissionException;
import com.jusika.backend.orderexecution.OrderSubmissionException;
import com.jusika.backend.orderpreview.OrderPreviewException;
import com.jusika.backend.orderpreview.OrderPreviewProperties;
import com.jusika.backend.orderpreview.OrderType;
import com.jusika.backend.toss.conditionalorder.TossConditionalOrderClient;

/** 실제 계좌를 건드리지 않고 조건 주문 취소의 승인·재검증·중복 방지 규칙을 검사합니다. */
class ConditionalOrderCancellationServiceTests {

	private static final long ACCOUNT_SEQ = 1L;
	private static final String CONDITIONAL_ORDER_ID = "conditional-order-id";
	private static final OffsetDateTime NOW = OffsetDateTime.of(
			2026, 9, 7, 12, 0, 0, 0, ZoneOffset.UTC);

	private RecordingConditionalOrderClient conditionalOrderClient;
	private MemoryPreviewStore previewStore;
	private MemoryExecutionStore executionStore;
	private RecordingCancellationGateway gateway;
	private ConditionalOrderCancellationService service;

	/** 각 테스트가 사용할 고정 시계와 메모리 저장소를 준비합니다. */
	@BeforeEach
	void 각_테스트에_필요한_조건_주문_취소_서비스를_준비한다() {
		conditionalOrderClient = new RecordingConditionalOrderClient();
		previewStore = new MemoryPreviewStore();
		executionStore = new MemoryExecutionStore();
		gateway = new RecordingCancellationGateway();
		Clock clock = Clock.fixed(Instant.parse("2026-09-07T12:00:00Z"), ZoneOffset.UTC);
		service = new ConditionalOrderCancellationService(
				conditionalOrderClient, previewStore, executionStore, gateway,
				new OrderPreviewProperties(Duration.ofMinutes(2)), clock);
	}

	/** OCO의 두 감시 조건과 상태를 승인용 변경 불가 사본에 모두 담는지 검사합니다. */
	@Test
	@DisplayName("감시 중 OCO 조건 주문의 취소 미리보기를 만든다")
	void 감시_중_OCO_조건_주문의_취소_미리보기를_만든다() {
		conditionalOrderClient.response = 조건_주문을_만든다(
				ConditionalOrderStatus.WATCHING,
				ConditionalOrderConditionStatus.WATCHING,
				ConditionalOrderConditionStatus.WATCHING,
				null);

		ConditionalOrderCancellationPreviewResponse response = service.createPreview(
				new ConditionalOrderCancellationPreviewRequest(
						ACCOUNT_SEQ, CONDITIONAL_ORDER_ID));

		assertThat(response.conditionalOrderType()).isEqualTo(ConditionalOrderType.OCO);
		assertThat(response.originalStatus()).isEqualTo(ConditionalOrderStatus.WATCHING);
		assertThat(response.first().triggerPrice()).isEqualByComparingTo("210");
		assertThat(response.second().orderPrice()).isEqualByComparingTo("189");
		assertThat(response.status())
				.isEqualTo(ConditionalOrderCancellationPreviewStatus.PENDING_APPROVAL);
		assertThat(conditionalOrderClient.callCount).isEqualTo(1);
		assertThat(gateway.callCount).isZero();
	}

	/** 이미 일반 주문을 만든 조건 주문은 취소 미리보기 전에 차단하는지 검사합니다. */
	@Test
	@DisplayName("이미 발동한 조건 주문은 안전 취소 대상에서 제외한다")
	void 이미_발동한_조건_주문은_안전_취소_대상에서_제외한다() {
		conditionalOrderClient.response = 조건_주문을_만든다(
				ConditionalOrderStatus.ORDERED,
				ConditionalOrderConditionStatus.ORDERED,
				ConditionalOrderConditionStatus.CANCELED,
				"triggered-order-id");

		assertThatThrownBy(() -> service.createPreview(
				new ConditionalOrderCancellationPreviewRequest(
						ACCOUNT_SEQ, CONDITIONAL_ORDER_ID)))
				.isInstanceOf(OrderPreviewException.class)
				.hasMessageContaining("이미 발동했거나 종료된 조건 주문");
		assertThat(previewStore.values).isEmpty();
		assertThat(gateway.callCount).isZero();
	}

	/** 승인 뒤 감시 조건 상태가 달라지면 취소 실행권을 만들지 않는지 검사합니다. */
	@Test
	@DisplayName("승인 뒤 조건 주문 상태가 바뀌면 취소 실행을 차단한다")
	void 승인_뒤_조건_주문_상태가_바뀌면_취소_실행을_차단한다() {
		conditionalOrderClient.response = 감시_중인_조건_주문을_만든다();
		ConditionalOrderCancellationPreviewResponse preview = 승인된_미리보기를_만든다();
		conditionalOrderClient.response = 조건_주문을_만든다(
				ConditionalOrderStatus.PAUSED,
				ConditionalOrderConditionStatus.PAUSED,
				ConditionalOrderConditionStatus.PAUSED,
				null);

		assertThatThrownBy(() -> service.executeApprovedPreview(preview.previewId()))
				.isInstanceOf(OrderExecutionConflictException.class)
				.hasMessageContaining("내용이나 상태가 변경");
		assertThat(executionStore.values).isEmpty();
		assertThat(gateway.callCount).isZero();
	}

	/** 같은 계좌의 같은 조건 주문을 여러 미리보기로도 한 번만 취소하는지 검사합니다. */
	@Test
	@DisplayName("같은 조건 주문은 여러 미리보기로도 한 번만 취소한다")
	void 같은_조건_주문은_여러_미리보기로도_한_번만_취소한다() {
		conditionalOrderClient.response = 감시_중인_조건_주문을_만든다();
		ConditionalOrderCancellationPreviewResponse first = 승인된_미리보기를_만든다();
		ConditionalOrderCancellationExecutionResponse accepted =
				service.executeApprovedPreview(first.previewId());
		ConditionalOrderCancellationPreviewResponse second = 승인된_미리보기를_만든다();

		assertThatThrownBy(() -> service.executeApprovedPreview(second.previewId()))
				.isInstanceOf(OrderExecutionConflictException.class);
		assertThat(accepted.status()).isEqualTo(OrderExecutionStatus.ACCEPTED);
		assertThat(accepted.brokerMode()).isEqualTo("MOCK");
		assertThat(service.getExecution(accepted.executionId())).isEqualTo(accepted);
		assertThat(gateway.callCount).isEqualTo(1);
	}

	/** 취소 응답이 불명확하면 UNKNOWN으로 저장하고 자동 재호출하지 않는지 검사합니다. */
	@Test
	@DisplayName("조건 주문 취소 결과 불명은 UNKNOWN으로 저장한다")
	void 조건_주문_취소_결과_불명은_UNKNOWN으로_저장한다() {
		conditionalOrderClient.response = 감시_중인_조건_주문을_만든다();
		ConditionalOrderCancellationPreviewResponse preview = 승인된_미리보기를_만든다();
		gateway.unknown = true;

		assertThatThrownBy(() -> service.executeApprovedPreview(preview.previewId()))
				.isInstanceOf(OrderExecutionSubmissionException.class)
				.hasMessageContaining("자동으로 다시 취소하지 마세요");
		ConditionalOrderCancellationExecutionResponse stored =
				executionStore.values.values().iterator().next();
		assertThat(stored.status()).isEqualTo(OrderExecutionStatus.UNKNOWN);
		assertThat(stored.failureType())
				.isEqualTo(OrderExecutionFailureType.SUBMISSION_UNKNOWN);
		assertThat(gateway.callCount).isEqualTo(1);
	}

	/** 생성과 승인을 연속 수행해 실행 가능한 조건 주문 취소 미리보기를 만듭니다. */
	private ConditionalOrderCancellationPreviewResponse 승인된_미리보기를_만든다() {
		ConditionalOrderCancellationPreviewResponse preview = service.createPreview(
				new ConditionalOrderCancellationPreviewRequest(
						ACCOUNT_SEQ, CONDITIONAL_ORDER_ID));
		return service.approvePreview(preview.previewId());
	}

	/** 반복 테스트에서 사용할 감시 중 OCO 조건 주문을 만듭니다. */
	private ConditionalOrderDetailResponse 감시_중인_조건_주문을_만든다() {
		return 조건_주문을_만든다(
				ConditionalOrderStatus.WATCHING,
				ConditionalOrderConditionStatus.WATCHING,
				ConditionalOrderConditionStatus.WATCHING,
				null);
	}

	/** 테스트 조건에 맞는 OCO 조건 주문 상세를 만듭니다. */
	private ConditionalOrderDetailResponse 조건_주문을_만든다(
			ConditionalOrderStatus status,
			ConditionalOrderConditionStatus firstStatus,
			ConditionalOrderConditionStatus secondStatus,
			String triggeredOrderId) {
		return new ConditionalOrderDetailResponse(
				ACCOUNT_SEQ, CONDITIONAL_ORDER_ID, ConditionalOrderType.OCO, status,
				"AAPL", ConditionalOrderMarket.US, new BigDecimal("10"), OrderType.LIMIT,
				LocalDate.of(2026, 9, 10),
				new Condition(
						ConditionalOrderConditionType.STOP, firstStatus,
						new BigDecimal("210"), null, new BigDecimal("209"),
						triggeredOrderId),
				new Condition(
						ConditionalOrderConditionType.STOP, secondStatus,
						new BigDecimal("190"), null, new BigDecimal("189"), null),
				NOW.minusMinutes(5));
	}

	/** 준비된 조건 주문 상세를 반환하고 조회 횟수를 기록하는 가짜 클라이언트입니다. */
	private static final class RecordingConditionalOrderClient extends TossConditionalOrderClient {
		private ConditionalOrderDetailResponse response;
		private int callCount;

		/** 실제 REST 연결 없이 부모 객체를 초기화합니다. */
		private RecordingConditionalOrderClient() {
			super(null, null);
		}

		/** 호출 횟수를 기록하고 준비된 조건 주문 상세를 반환합니다. */
		@Override
		public ConditionalOrderDetailResponse getConditionalOrder(
				long accountSeq, String conditionalOrderId) {
			callCount++;
			return response;
		}
	}

	/** 조건 주문 취소 미리보기의 상태 변화를 메모리에서 재현합니다. */
	private static final class MemoryPreviewStore
			implements ConditionalOrderCancellationPreviewStore {
		private final Map<String, ConditionalOrderCancellationPreviewResponse> values =
				new HashMap<>();

		/** 새 미리보기를 저장합니다. */
		@Override
		public ConditionalOrderCancellationPreviewResponse save(
				ConditionalOrderCancellationPreviewResponse preview) {
			values.put(preview.previewId(), preview);
			return preview;
		}

		/** 승인 가능한 미리보기를 승인 상태로 변경합니다. */
		@Override
		public boolean approvePending(String id, OffsetDateTime at) {
			ConditionalOrderCancellationPreviewResponse value = values.get(id);
			if (value == null
					|| value.status()
							!= ConditionalOrderCancellationPreviewStatus.PENDING_APPROVAL
					|| !value.expiresAt().isAfter(at)) {
				return false;
			}
			values.put(id, 상태를_바꾼다(
					value, ConditionalOrderCancellationPreviewStatus.APPROVED, at));
			return true;
		}

		/** 메모리 테스트에서는 시간이 고정되어 만료 상태를 바꾸지 않습니다. */
		@Override
		public boolean expirePending(String id, OffsetDateTime now) {
			return false;
		}

		/** 승인된 미리보기를 실행에 사용된 상태로 변경합니다. */
		@Override
		public boolean consumeApproved(String id, OffsetDateTime at) {
			ConditionalOrderCancellationPreviewResponse value = values.get(id);
			if (value == null
					|| value.status() != ConditionalOrderCancellationPreviewStatus.APPROVED) {
				return false;
			}
			values.put(id, 상태를_바꾼다(
					value, ConditionalOrderCancellationPreviewStatus.CONSUMED,
					value.approvedAt()));
			return true;
		}

		/** 식별값으로 저장된 미리보기를 조회합니다. */
		@Override
		public Optional<ConditionalOrderCancellationPreviewResponse> findById(String id) {
			return Optional.ofNullable(values.get(id));
		}

		/** 원래 조건 주문 값을 유지한 채 미리보기 상태와 승인 시각만 바꿉니다. */
		private ConditionalOrderCancellationPreviewResponse 상태를_바꾼다(
				ConditionalOrderCancellationPreviewResponse value,
				ConditionalOrderCancellationPreviewStatus status,
				OffsetDateTime approvedAt) {
			return new ConditionalOrderCancellationPreviewResponse(
					value.previewId(), value.createdAt(), value.expiresAt(), value.accountSeq(),
					value.conditionalOrderId(), value.conditionalOrderType(),
					value.originalStatus(), value.symbol(), value.market(), value.quantity(),
					value.orderType(), value.expireDate(), value.first(), value.second(),
					value.conditionalOrderCreatedAt(), status, approvedAt);
		}
	}

	/** 조건 주문 취소 실행의 중복 방지와 상태 변화를 메모리에서 재현합니다. */
	private static final class MemoryExecutionStore
			implements ConditionalOrderCancellationExecutionStore {
		private final Map<String, ConditionalOrderCancellationExecutionResponse> values =
				new HashMap<>();

		/** 같은 계좌의 같은 조건 주문에는 첫 실행만 저장합니다. */
		@Override
		public boolean claim(ConditionalOrderCancellationExecutionResponse execution) {
			if (values.values().stream().anyMatch(value ->
					value.accountSeq() == execution.accountSeq()
							&& value.conditionalOrderId().equals(execution.conditionalOrderId()))) {
				return false;
			}
			values.put(execution.executionId(), execution);
			return true;
		}

		/** 실행을 제출 중 상태로 변경합니다. */
		@Override
		public boolean markSubmitting(String id, OffsetDateTime at) {
			return 변경한다(id, OrderExecutionStatus.SUBMITTING, null, at, null);
		}

		/** 실행을 내부 상태 오류로 종료합니다. */
		@Override
		public boolean markPreparationFailed(String id, OffsetDateTime at) {
			return 변경한다(
					id, OrderExecutionStatus.REJECTED,
					OrderExecutionFailureType.INTERNAL_STATE, null, at);
		}

		/** 실행을 취소 성공 상태로 변경합니다. */
		@Override
		public boolean markAccepted(String id, OffsetDateTime at) {
			return 변경한다(id, OrderExecutionStatus.ACCEPTED, null, null, at);
		}

		/** 실행을 증권사 거절 상태로 변경합니다. */
		@Override
		public boolean markRejected(String id, OffsetDateTime at) {
			return 변경한다(
					id, OrderExecutionStatus.REJECTED,
					OrderExecutionFailureType.BROKER_REJECTED, null, at);
		}

		/** 실행을 결과 불명 상태로 변경합니다. */
		@Override
		public boolean markUnknown(String id, OffsetDateTime at) {
			return 변경한다(
					id, OrderExecutionStatus.UNKNOWN,
					OrderExecutionFailureType.SUBMISSION_UNKNOWN, null, null);
		}

		/** 실행 식별값으로 저장된 결과를 조회합니다. */
		@Override
		public Optional<ConditionalOrderCancellationExecutionResponse> findById(String id) {
			return Optional.ofNullable(values.get(id));
		}

		/** 미리보기 식별값으로 저장된 결과를 조회합니다. */
		@Override
		public Optional<ConditionalOrderCancellationExecutionResponse> findByPreviewId(String id) {
			return values.values().stream()
					.filter(value -> value.previewId().equals(id))
					.findFirst();
		}

		/** 계좌와 조건 주문 식별값으로 저장된 결과를 조회합니다. */
		@Override
		public Optional<ConditionalOrderCancellationExecutionResponse> findByTarget(
				long accountSeq, String conditionalOrderId) {
			return values.values().stream()
					.filter(value -> value.accountSeq() == accountSeq
							&& value.conditionalOrderId().equals(conditionalOrderId))
					.findFirst();
		}

		/** 기존 식별값과 시각을 유지하며 실행 상태 필드만 변경합니다. */
		private boolean 변경한다(
				String id,
				OrderExecutionStatus status,
				OrderExecutionFailureType failure,
				OffsetDateTime submitted,
				OffsetDateTime completed) {
			ConditionalOrderCancellationExecutionResponse value = values.get(id);
			if (value == null) {
				return false;
			}
			OffsetDateTime updated = completed != null
					? completed : submitted != null ? submitted : NOW;
			values.put(id, new ConditionalOrderCancellationExecutionResponse(
					value.executionId(), value.previewId(), value.accountSeq(),
					value.conditionalOrderId(), value.brokerMode(), status, failure,
					value.createdAt(), updated,
					submitted != null ? submitted : value.submittedAt(), completed));
			return true;
		}
	}

	/** 취소 호출 횟수를 기록하고 정상 또는 결과 불명 응답을 재현합니다. */
	private static final class RecordingCancellationGateway
			implements ConditionalOrderCancellationGateway {
		private int callCount;
		private boolean unknown;

		/** 취소 호출을 기록하고 설정에 따라 정상 처리하거나 결과 불명 오류를 냅니다. */
		@Override
		public void cancelConditionalOrder(long accountSeq, String conditionalOrderId) {
			callCount++;
			if (unknown) {
				throw new OrderSubmissionException("테스트 결과 불명", true);
			}
		}

		/** 테스트가 사용하는 모의 모드 이름을 반환합니다. */
		@Override
		public String mode() {
			return "MOCK";
		}
	}
}
