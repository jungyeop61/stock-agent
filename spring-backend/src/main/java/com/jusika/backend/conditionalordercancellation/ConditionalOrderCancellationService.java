package com.jusika.backend.conditionalordercancellation;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.jusika.backend.conditionalorder.ConditionalOrderConditionStatus;
import com.jusika.backend.conditionalorder.ConditionalOrderDetailResponse;
import com.jusika.backend.conditionalorder.ConditionalOrderDetailResponse.Condition;
import com.jusika.backend.conditionalorder.ConditionalOrderStatus;
import com.jusika.backend.conditionalorder.ConditionalOrderType;
import com.jusika.backend.conditionalordercancellation.ConditionalOrderCancellationPreviewResponse.ConditionSnapshot;
import com.jusika.backend.orderexecution.OrderExecutionConflictException;
import com.jusika.backend.orderexecution.OrderExecutionNotFoundException;
import com.jusika.backend.orderexecution.OrderExecutionStatus;
import com.jusika.backend.orderexecution.OrderExecutionSubmissionException;
import com.jusika.backend.orderexecution.OrderSubmissionException;
import com.jusika.backend.orderpreview.OrderPreviewException;
import com.jusika.backend.orderpreview.OrderPreviewExpiredException;
import com.jusika.backend.orderpreview.OrderPreviewNotFoundException;
import com.jusika.backend.orderpreview.OrderPreviewProperties;
import com.jusika.backend.orderpreview.OrderPreviewStateException;
import com.jusika.backend.toss.conditionalorder.TossConditionalOrderClient;

/** 조건 주문을 재조회해 승인용 사본을 만들고 중복 없는 안전한 모의 취소를 실행합니다. */
@Service
public class ConditionalOrderCancellationService {

	private static final Set<ConditionalOrderStatus> CANCELLABLE_STATUSES = Set.of(
			ConditionalOrderStatus.WATCHING,
			ConditionalOrderStatus.PAUSED);
	private static final Set<ConditionalOrderConditionStatus> CANCELLABLE_CONDITION_STATUSES = Set.of(
			ConditionalOrderConditionStatus.WATCHING,
			ConditionalOrderConditionStatus.HOLDING,
			ConditionalOrderConditionStatus.PAUSED);

	private final TossConditionalOrderClient conditionalOrderClient;
	private final ConditionalOrderCancellationPreviewStore previewStore;
	private final ConditionalOrderCancellationExecutionStore executionStore;
	private final ConditionalOrderCancellationGateway cancellationGateway;
	private final OrderPreviewProperties properties;
	private final Clock clock;

	/** 조건 주문 조회, 상태 저장, 취소 경계와 미리보기 유효시간 설정을 전달받습니다. */
	public ConditionalOrderCancellationService(
			TossConditionalOrderClient conditionalOrderClient,
			ConditionalOrderCancellationPreviewStore previewStore,
			ConditionalOrderCancellationExecutionStore executionStore,
			ConditionalOrderCancellationGateway cancellationGateway,
			OrderPreviewProperties properties,
			Clock clock) {
		this.conditionalOrderClient = conditionalOrderClient;
		this.previewStore = previewStore;
		this.executionStore = executionStore;
		this.cancellationGateway = cancellationGateway;
		this.properties = properties;
		this.clock = clock;
	}

	/** 최신 조건 주문을 조회하고 취소 승인용 변경 불가 미리보기를 저장합니다. */
	public ConditionalOrderCancellationPreviewResponse createPreview(
			ConditionalOrderCancellationPreviewRequest request) {
		validateRequest(request);
		ConditionalOrderDetailResponse order = conditionalOrderClient.getConditionalOrder(
				request.accountSeq(), request.conditionalOrderId());
		validateOrderIdentity(order, request.accountSeq(), request.conditionalOrderId());
		validateCancellableOrder(order);

		OffsetDateTime createdAt = OffsetDateTime.now(clock);
		ConditionalOrderCancellationPreviewResponse preview =
				new ConditionalOrderCancellationPreviewResponse(
						UUID.randomUUID().toString(), createdAt,
						createdAt.plus(properties.expiration()), order.accountSeq(),
						order.conditionalOrderId(), order.type(), order.status(), order.symbol(),
						order.market(), order.quantity(), order.orderType(), order.expireDate(),
						toSnapshot(order.first()), toSnapshot(order.second()), order.createdAt(),
						ConditionalOrderCancellationPreviewStatus.PENDING_APPROVAL, null);
		return previewStore.save(preview);
	}

	/** 유효한 승인 대기 조건 주문 취소 미리보기만 한 번 승인합니다. */
	public ConditionalOrderCancellationPreviewResponse approvePreview(String previewId) {
		validateUuid(previewId, "조건 주문 취소 미리보기");
		OffsetDateTime approvedAt = OffsetDateTime.now(clock);
		previewStore.expirePending(previewId, approvedAt);
		if (previewStore.approvePending(previewId, approvedAt)) {
			return findPreview(previewId);
		}
		ConditionalOrderCancellationPreviewResponse preview = findPreview(previewId);
		switch (preview.status()) {
			case EXPIRED -> throw new OrderPreviewExpiredException(
					"조건 주문 취소 미리보기의 승인 시간이 지났습니다. 새 미리보기를 만들어 주세요.");
			case APPROVED -> throw new OrderPreviewStateException(
					"이미 승인한 조건 주문 취소 미리보기입니다.");
			case CONSUMED -> throw new OrderPreviewStateException(
					"이미 취소에 사용한 조건 주문 미리보기입니다.");
			case PENDING_APPROVAL -> throw new OrderPreviewStateException(
					"조건 주문 취소 미리보기 상태가 변경되어 승인하지 못했습니다.");
		}
		throw new IllegalStateException("처리할 수 없는 조건 주문 취소 미리보기 상태입니다.");
	}

	/** 승인 사본과 최신 조건 주문을 비교한 뒤 같은 대상에 한 번만 모의 취소를 실행합니다. */
	public ConditionalOrderCancellationExecutionResponse executeApprovedPreview(String previewId) {
		validateUuid(previewId, "조건 주문 취소 미리보기");
		ConditionalOrderCancellationPreviewResponse preview = findPreview(previewId);
		OffsetDateTime startedAt = OffsetDateTime.now(clock);
		validateExecutablePreview(preview, startedAt);
		cancellationGateway.requireCancellationAvailable();

		ConditionalOrderDetailResponse current = conditionalOrderClient.getConditionalOrder(
				preview.accountSeq(), preview.conditionalOrderId());
		validateOrderIdentity(current, preview.accountSeq(), preview.conditionalOrderId());
		validateUnchangedOrder(preview, current);
		validateCancellableOrder(current);

		String executionId = UUID.randomUUID().toString();
		ConditionalOrderCancellationExecutionResponse prepared =
				new ConditionalOrderCancellationExecutionResponse(
						executionId, preview.previewId(), preview.accountSeq(),
						preview.conditionalOrderId(), cancellationGateway.mode(),
						OrderExecutionStatus.PREPARED, null, startedAt, startedAt, null, null);
		if (!executionStore.claim(prepared)) {
			throw new OrderExecutionConflictException(
					"이미 취소했거나 취소 중인 조건 주문입니다.");
		}
		if (!previewStore.consumeApproved(previewId, startedAt)) {
			executionStore.markPreparationFailed(executionId, startedAt);
			throw new OrderExecutionConflictException(
					"조건 주문 취소 미리보기의 승인 상태가 변경되었습니다.");
		}
		if (!executionStore.markSubmitting(executionId, startedAt)) {
			executionStore.markPreparationFailed(executionId, startedAt);
			throw new OrderExecutionSubmissionException(
					"조건 주문 취소 실행 상태를 제출 중으로 변경하지 못했습니다.");
		}
		return cancelAndRecord(
				executionId, preview.accountSeq(), preview.conditionalOrderId());
	}

	/** 실행 식별값으로 저장된 조건 주문 취소 실행 상태를 조회합니다. */
	public ConditionalOrderCancellationExecutionResponse getExecution(String executionId) {
		validateUuid(executionId, "조건 주문 취소 실행");
		return executionStore.findById(executionId)
				.orElseThrow(() -> new OrderExecutionNotFoundException(
						"조건 주문 취소 실행 기록을 찾을 수 없습니다."));
	}

	/** 취소 게이트웨이 결과를 성공·확정 거절·결과 불명으로 나눠 저장합니다. */
	private ConditionalOrderCancellationExecutionResponse cancelAndRecord(
			String executionId, long accountSeq, String conditionalOrderId) {
		try {
			cancellationGateway.cancelConditionalOrder(accountSeq, conditionalOrderId);
			OffsetDateTime completedAt = OffsetDateTime.now(clock);
			if (!executionStore.markAccepted(executionId, completedAt)) {
				throw new OrderExecutionSubmissionException(
						"조건 주문 취소 성공 결과를 데이터베이스에 기록하지 못했습니다.");
			}
			return findExecution(executionId);
		} catch (OrderSubmissionException exception) {
			OffsetDateTime failedAt = OffsetDateTime.now(clock);
			if (exception.isSubmissionStateUnknown()) {
				executionStore.markUnknown(executionId, failedAt);
				throw new OrderExecutionSubmissionException(
						"조건 주문 취소 여부를 확인할 수 없습니다. 자동으로 다시 취소하지 마세요.");
			}
			executionStore.markRejected(executionId, failedAt);
			throw new OrderExecutionSubmissionException("증권사가 조건 주문 취소를 거절했습니다.");
		} catch (OrderExecutionSubmissionException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			executionStore.markUnknown(executionId, OffsetDateTime.now(clock));
			throw new OrderExecutionSubmissionException(
					"조건 주문 취소 여부를 확인할 수 없습니다. 자동으로 다시 취소하지 마세요.");
		}
	}

	/** 외부 조회 전에 계좌와 조건 주문 식별값 형식을 검사합니다. */
	private void validateRequest(ConditionalOrderCancellationPreviewRequest request) {
		if (request == null) {
			throw new OrderPreviewException("조건 주문 취소 미리보기 요청이 필요합니다.");
		}
		if (request.accountSeq() <= 0) {
			throw new OrderPreviewException("계좌 식별값은 1 이상이어야 합니다.");
		}
		validateConditionalOrderId(request.conditionalOrderId());
	}

	/** 조건 주문 식별값이 경로를 오염시키지 않는 안전한 문자열인지 검사합니다. */
	private void validateConditionalOrderId(String conditionalOrderId) {
		if (conditionalOrderId == null
				|| conditionalOrderId.isBlank()
				|| conditionalOrderId.length() > 512
				|| conditionalOrderId.chars().anyMatch(Character::isWhitespace)
				|| conditionalOrderId.chars().anyMatch(Character::isISOControl)) {
			throw new OrderPreviewException("조건 주문 식별값 형식이 올바르지 않습니다.");
		}
	}

	/** 조회 결과가 요청한 계좌와 조건 주문을 정확히 가리키고 필수값을 가졌는지 검사합니다. */
	private void validateOrderIdentity(
			ConditionalOrderDetailResponse order,
			long accountSeq,
			String conditionalOrderId) {
		if (order == null
				|| order.accountSeq() != accountSeq
				|| !conditionalOrderId.equals(order.conditionalOrderId())) {
			throw new OrderPreviewException(
					"조회한 조건 주문이 취소 요청 대상과 일치하지 않습니다.");
		}
		if (order.type() == null
				|| order.status() == null
				|| order.symbol() == null
				|| order.symbol().isBlank()
				|| order.market() == null
				|| order.quantity() == null
				|| order.quantity().signum() <= 0
				|| order.orderType() == null
				|| order.first() == null
				|| order.createdAt() == null) {
			throw new OrderPreviewException("조건 주문 취소 판단에 필요한 정보가 부족합니다.");
		}
		if ((order.type() == ConditionalOrderType.SINGLE && order.second() != null)
				|| (order.type() != ConditionalOrderType.SINGLE && order.second() == null)) {
			throw new OrderPreviewException("조건 주문 유형과 감시 조건 구성이 일치하지 않습니다.");
		}
		validateConditionShape(order.first());
		if (order.second() != null) {
			validateConditionShape(order.second());
		}
	}

	/** 감시 조건에 비교와 취소 판단에 필요한 유형과 상태가 있는지 검사합니다. */
	private void validateConditionShape(Condition condition) {
		if (condition.type() == null || condition.status() == null) {
			throw new OrderPreviewException("조건 주문 감시 조건 정보가 부족합니다.");
		}
	}

	/** 아직 일반 주문을 만들지 않은 감시·일시중지 상태만 안전한 취소 대상으로 허용합니다. */
	private void validateCancellableOrder(ConditionalOrderDetailResponse order) {
		if (!CANCELLABLE_STATUSES.contains(order.status())) {
			throw new OrderPreviewException(
					"이미 발동했거나 종료된 조건 주문은 이 안전 취소 기능으로 취소할 수 없습니다.");
		}
		validateCancellableCondition(order.first());
		if (order.second() != null) {
			validateCancellableCondition(order.second());
		}
	}

	/** 개별 조건이 감시 가능 상태이며 발동 일반 주문을 만들지 않았는지 확인합니다. */
	private void validateCancellableCondition(Condition condition) {
		if (!CANCELLABLE_CONDITION_STATUSES.contains(condition.status())
				|| (condition.triggeredOrderId() != null
						&& !condition.triggeredOrderId().isBlank())) {
			throw new OrderPreviewException(
					"이미 발동했거나 종료된 감시 조건이 있어 조건 주문을 안전하게 취소할 수 없습니다.");
		}
	}

	/** 승인 시점 사본과 실행 직전 조건 주문의 모든 의미 있는 값이 같은지 검사합니다. */
	private void validateUnchangedOrder(
			ConditionalOrderCancellationPreviewResponse preview,
			ConditionalOrderDetailResponse current) {
		if (preview.conditionalOrderType() != current.type()
				|| preview.originalStatus() != current.status()
				|| !Objects.equals(preview.symbol(), current.symbol())
				|| preview.market() != current.market()
				|| !sameDecimal(preview.quantity(), current.quantity())
				|| preview.orderType() != current.orderType()
				|| !Objects.equals(preview.expireDate(), current.expireDate())
				|| !sameCondition(preview.first(), current.first())
				|| !sameCondition(preview.second(), current.second())
				|| !sameInstant(preview.conditionalOrderCreatedAt(), current.createdAt())) {
			throw new OrderExecutionConflictException(
					"승인 뒤 조건 주문 내용이나 상태가 변경되었습니다. 새 취소 미리보기를 만들어 주세요.");
		}
	}

	/** 저장된 감시 조건 사본과 최신 조건의 상태·가격·발동 주문이 같은지 비교합니다. */
	private boolean sameCondition(ConditionSnapshot snapshot, Condition current) {
		if (snapshot == null || current == null) {
			return snapshot == null && current == null;
		}
		return snapshot.type() == current.type()
				&& snapshot.status() == current.status()
				&& sameDecimal(snapshot.triggerPrice(), current.triggerPrice())
				&& sameDecimal(snapshot.targetProfitRate(), current.targetProfitRate())
				&& sameDecimal(snapshot.orderPrice(), current.orderPrice())
				&& Objects.equals(snapshot.triggeredOrderId(), current.triggeredOrderId());
	}

	/** 소수 자릿수 표현이 달라도 실제 금융값이 같은지 비교합니다. */
	private boolean sameDecimal(BigDecimal left, BigDecimal right) {
		return left == null ? right == null : right != null && left.compareTo(right) == 0;
	}

	/** 데이터베이스가 시간대 표기를 바꿔도 실제 같은 시각인지 비교합니다. */
	private boolean sameInstant(OffsetDateTime left, OffsetDateTime right) {
		return left == null ? right == null : right != null && left.isEqual(right);
	}

	/** 조회한 감시 조건을 데이터베이스에 저장할 변경 불가 사본으로 변환합니다. */
	private ConditionSnapshot toSnapshot(Condition condition) {
		if (condition == null) {
			return null;
		}
		return new ConditionSnapshot(
				condition.type(), condition.status(), condition.triggerPrice(),
				condition.targetProfitRate(), condition.orderPrice(), condition.triggeredOrderId());
	}

	/** 승인 상태이고 유효시간이 지나지 않은 미리보기인지 검사합니다. */
	private void validateExecutablePreview(
			ConditionalOrderCancellationPreviewResponse preview,
			OffsetDateTime now) {
		if (preview.status() != ConditionalOrderCancellationPreviewStatus.APPROVED) {
			throw new OrderPreviewStateException(
					"승인된 조건 주문 취소 미리보기만 실행할 수 있습니다.");
		}
		if (!preview.expiresAt().isAfter(now)) {
			throw new OrderPreviewExpiredException(
					"조건 주문 취소 미리보기의 실행 시간이 지났습니다. 새 미리보기를 만들어 주세요.");
		}
	}

	/** UUID 경로 식별값 형식을 검사합니다. */
	private void validateUuid(String value, String name) {
		if (value == null) {
			throw new OrderPreviewException(name + " 식별값이 필요합니다.");
		}
		try {
			UUID.fromString(value);
		} catch (IllegalArgumentException exception) {
			throw new OrderPreviewException(name + " 식별값 형식이 올바르지 않습니다.");
		}
	}

	/** 저장소에서 조건 주문 취소 미리보기를 찾거나 찾을 수 없음 오류를 냅니다. */
	private ConditionalOrderCancellationPreviewResponse findPreview(String previewId) {
		return previewStore.findById(previewId)
				.orElseThrow(() -> new OrderPreviewNotFoundException(
						"조건 주문 취소 미리보기를 찾을 수 없습니다."));
	}

	/** 저장소에서 조건 주문 취소 실행을 찾거나 내부 저장 오류를 냅니다. */
	private ConditionalOrderCancellationExecutionResponse findExecution(String executionId) {
		return executionStore.findById(executionId)
				.orElseThrow(() -> new OrderExecutionSubmissionException(
						"저장된 조건 주문 취소 실행 결과를 찾지 못했습니다."));
	}
}
