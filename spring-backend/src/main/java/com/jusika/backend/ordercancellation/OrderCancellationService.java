package com.jusika.backend.ordercancellation;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.jusika.backend.order.OrderOperationResponse;
import com.jusika.backend.orderexecution.OrderExecutionConflictException;
import com.jusika.backend.orderexecution.OrderExecutionNotFoundException;
import com.jusika.backend.orderexecution.OrderExecutionStatus;
import com.jusika.backend.orderexecution.OrderExecutionSubmissionException;
import com.jusika.backend.orderexecution.OrderSubmissionException;
import com.jusika.backend.orderhistory.OrderDetailResponse;
import com.jusika.backend.orderhistory.OrderStatus;
import com.jusika.backend.orderpreview.OrderPreviewException;
import com.jusika.backend.orderpreview.OrderPreviewExpiredException;
import com.jusika.backend.orderpreview.OrderPreviewNotFoundException;
import com.jusika.backend.orderpreview.OrderPreviewProperties;
import com.jusika.backend.orderpreview.OrderPreviewStateException;
import com.jusika.backend.toss.orderhistory.TossOrderHistoryClient;

/** 취소 가능한 주문을 조회해 승인용 사본을 만들고 중복 없는 모의 취소를 실행합니다. */
@Service
public class OrderCancellationService {

	private final TossOrderHistoryClient historyClient;
	private final OrderCancellationPreviewStore previewStore;
	private final OrderCancellationExecutionStore executionStore;
	private final OrderCancellationGateway cancellationGateway;
	private final OrderPreviewProperties properties;
	private final Clock clock;

	/** 취소 대상 조회, 상태 저장, 취소 경계와 유효시간 설정을 전달받습니다. */
	public OrderCancellationService(
			TossOrderHistoryClient historyClient,
			OrderCancellationPreviewStore previewStore,
			OrderCancellationExecutionStore executionStore,
			OrderCancellationGateway cancellationGateway,
			OrderPreviewProperties properties,
			Clock clock) {
		this.historyClient = historyClient;
		this.previewStore = previewStore;
		this.executionStore = executionStore;
		this.cancellationGateway = cancellationGateway;
		this.properties = properties;
		this.clock = clock;
	}

	/** 토스증권에서 현재 주문 상태를 읽고 취소 승인용 변경 불가 미리보기를 저장합니다. */
	public OrderCancellationPreviewResponse createPreview(OrderCancellationPreviewRequest request) {
		validateRequest(request);
		OrderDetailResponse order = historyClient.getOrder(request.accountSeq(), request.orderId());
		validateOrderIdentity(order, request.accountSeq(), request.orderId());
		CancellationAmounts amounts = validateCancelableOrder(order);
		OffsetDateTime createdAt = OffsetDateTime.now(clock);
		OrderCancellationPreviewResponse preview = new OrderCancellationPreviewResponse(
				UUID.randomUUID().toString(), createdAt, createdAt.plus(properties.expiration()),
				order.accountSeq(), order.orderId(), order.symbol(), order.side(),
				order.orderTypeCode(), order.status(), order.price(), order.quantity(),
				amounts.filledQuantity(), amounts.remainingQuantity(), order.orderAmount(),
				order.currency(), OrderCancellationPreviewStatus.PENDING_APPROVAL, null);
		return previewStore.save(preview);
	}

	/** 유효한 승인 대기 취소 미리보기만 한 번 승인합니다. */
	public OrderCancellationPreviewResponse approvePreview(String previewId) {
		validateUuid(previewId, "취소 미리보기");
		OffsetDateTime approvedAt = OffsetDateTime.now(clock);
		previewStore.expirePending(previewId, approvedAt);
		if (previewStore.approvePending(previewId, approvedAt)) {
			return findPreview(previewId);
		}
		OrderCancellationPreviewResponse preview = findPreview(previewId);
		switch (preview.status()) {
			case EXPIRED -> throw new OrderPreviewExpiredException(
					"취소 미리보기의 승인 시간이 지났습니다. 새 미리보기를 만들어 주세요.");
			case APPROVED -> throw new OrderPreviewStateException("이미 승인한 취소 미리보기입니다.");
			case CONSUMED -> throw new OrderPreviewStateException("이미 취소에 사용한 미리보기입니다.");
			case PENDING_APPROVAL -> throw new OrderPreviewStateException(
					"취소 미리보기 상태가 변경되어 승인하지 못했습니다.");
		}
		throw new IllegalStateException("처리할 수 없는 취소 미리보기 상태입니다.");
	}

	/** 승인된 사본과 현재 주문이 같은지 재검증한 뒤 원주문당 한 번만 모의 취소합니다. */
	public OrderCancellationExecutionResponse executeApprovedPreview(String previewId) {
		validateUuid(previewId, "취소 미리보기");
		OrderCancellationPreviewResponse preview = findPreview(previewId);
		OffsetDateTime startedAt = OffsetDateTime.now(clock);
		validateExecutablePreview(preview, startedAt);

		OrderDetailResponse current = historyClient.getOrder(preview.accountSeq(), preview.orderId());
		validateOrderIdentity(current, preview.accountSeq(), preview.orderId());
		validateUnchangedOrder(preview, current);
		validateCancelableOrder(current);

		String executionId = UUID.randomUUID().toString();
		OrderCancellationExecutionResponse prepared = new OrderCancellationExecutionResponse(
				executionId, preview.previewId(), preview.orderId(), null, cancellationGateway.mode(),
				OrderExecutionStatus.PREPARED, null, startedAt, startedAt, null, null);
		if (!executionStore.claim(prepared)) {
			throw new OrderExecutionConflictException("이미 취소했거나 취소 중인 주문입니다.");
		}
		if (!previewStore.consumeApproved(previewId, startedAt)) {
			executionStore.markPreparationFailed(executionId, startedAt);
			throw new OrderExecutionConflictException("취소 미리보기의 승인 상태가 변경되었습니다.");
		}
		if (!executionStore.markSubmitting(executionId, startedAt)) {
			executionStore.markPreparationFailed(executionId, startedAt);
			throw new OrderExecutionSubmissionException("취소 실행 상태를 제출 중으로 변경하지 못했습니다.");
		}
		return cancelAndRecord(executionId, preview.accountSeq(), preview.orderId());
	}

	/** 실행 식별값으로 저장된 취소 실행 상태를 조회합니다. */
	public OrderCancellationExecutionResponse getExecution(String executionId) {
		validateUuid(executionId, "취소 실행");
		return executionStore.findById(executionId)
				.orElseThrow(() -> new OrderExecutionNotFoundException("취소 실행 기록을 찾을 수 없습니다."));
	}

	/** 취소 게이트웨이 결과를 접수·거절·결과 불명으로 나눠 저장합니다. */
	private OrderCancellationExecutionResponse cancelAndRecord(
			String executionId, long accountSeq, String orderId) {
		try {
			OrderOperationResponse result = cancellationGateway.cancelOrder(accountSeq, orderId);
			if (result == null || result.orderId() == null || result.orderId().isBlank()
					|| orderId.equals(result.orderId())) {
				throw new OrderSubmissionException("주문 취소 응답 형식이 올바르지 않습니다.", true);
			}
			OffsetDateTime completedAt = OffsetDateTime.now(clock);
			if (!executionStore.markAccepted(executionId, result.orderId(), completedAt)) {
				throw new OrderExecutionSubmissionException("취소 접수 결과를 데이터베이스에 기록하지 못했습니다.");
			}
			return findExecution(executionId);
		} catch (OrderSubmissionException exception) {
			OffsetDateTime failedAt = OffsetDateTime.now(clock);
			if (exception.isSubmissionStateUnknown()) {
				executionStore.markUnknown(executionId, failedAt);
				throw new OrderExecutionSubmissionException(
						"주문 취소 접수 여부를 확인할 수 없습니다. 자동으로 다시 취소하지 마세요.");
			}
			executionStore.markRejected(executionId, failedAt);
			throw new OrderExecutionSubmissionException("증권사가 주문 취소를 거절했습니다.");
		} catch (OrderExecutionSubmissionException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			executionStore.markUnknown(executionId, OffsetDateTime.now(clock));
			throw new OrderExecutionSubmissionException(
					"주문 취소 접수 여부를 확인할 수 없습니다. 자동으로 다시 취소하지 마세요.");
		}
	}

	/** 외부 조회 전에 계좌와 주문 식별값 형식을 검사합니다. */
	private void validateRequest(OrderCancellationPreviewRequest request) {
		if (request == null) {
			throw new OrderPreviewException("취소 미리보기 요청이 필요합니다.");
		}
		if (request.accountSeq() <= 0) {
			throw new OrderPreviewException("계좌 식별값은 1 이상이어야 합니다.");
		}
		validateOrderId(request.orderId());
	}

	/** 주문 식별값이 헤더나 경로를 오염시키지 않는 안전한 문자열인지 검사합니다. */
	private void validateOrderId(String orderId) {
		if (orderId == null || orderId.isBlank() || orderId.length() > 512
				|| orderId.chars().anyMatch(Character::isWhitespace)
				|| orderId.chars().anyMatch(Character::isISOControl)) {
			throw new OrderPreviewException("주문 식별값 형식이 올바르지 않습니다.");
		}
	}

	/** 조회 응답이 요청한 계좌와 주문을 정확히 가리키는지 검사합니다. */
	private void validateOrderIdentity(OrderDetailResponse order, long accountSeq, String orderId) {
		if (order == null || order.accountSeq() != accountSeq || !orderId.equals(order.orderId())) {
			throw new OrderPreviewException("조회한 주문이 취소 요청 대상과 일치하지 않습니다.");
		}
		if (order.symbol() == null || order.symbol().isBlank() || order.side() == null
				|| order.orderTypeCode() == null || order.orderTypeCode().isBlank()
				|| order.status() == null || order.currency() == null || order.currency().isBlank()) {
			throw new OrderPreviewException("취소 판단에 필요한 주문 정보가 부족합니다.");
		}
	}

	/** 미체결 또는 일부 체결 주문인지 확인하고 체결·잔여 수량을 계산합니다. */
	private CancellationAmounts validateCancelableOrder(OrderDetailResponse order) {
		if (order.status() != OrderStatus.PENDING && order.status() != OrderStatus.PARTIAL_FILLED) {
			throw new OrderPreviewException("현재 상태에서는 주문을 취소할 수 없습니다.");
		}
		BigDecimal filled = order.execution() == null ? null : order.execution().filledQuantity();
		if (filled == null || filled.signum() < 0) {
			throw new OrderPreviewException("누적 체결 수량이 올바르지 않습니다.");
		}
		if (order.quantity() != null) {
			if (order.quantity().signum() <= 0 || filled.compareTo(order.quantity()) > 0) {
				throw new OrderPreviewException("주문 수량과 누적 체결 수량이 올바르지 않습니다.");
			}
			BigDecimal remaining = order.quantity().subtract(filled);
			if (remaining.signum() <= 0) {
				throw new OrderPreviewException("취소할 미체결 수량이 없습니다.");
			}
			return new CancellationAmounts(filled, remaining);
		}
		if (order.orderAmount() == null || order.orderAmount().signum() <= 0) {
			throw new OrderPreviewException("취소할 주문의 수량 또는 금액 정보가 올바르지 않습니다.");
		}
		return new CancellationAmounts(filled, null);
	}

	/** 승인 시점의 원주문과 실행 직전 원주문의 변경 불가 값이 같은지 검사합니다. */
	private void validateUnchangedOrder(
			OrderCancellationPreviewResponse preview, OrderDetailResponse current) {
		if (!Objects.equals(preview.symbol(), current.symbol())
				|| preview.side() != current.side()
				|| !Objects.equals(preview.orderTypeCode(), current.orderTypeCode())
				|| !Objects.equals(preview.currency(), current.currency())
				|| !sameDecimal(preview.price(), current.price())
				|| !sameDecimal(preview.quantity(), current.quantity())
				|| !sameDecimal(preview.orderAmount(), current.orderAmount())) {
			throw new OrderExecutionConflictException(
					"승인 뒤 주문 내용이 변경되었습니다. 새 취소 미리보기를 만들어 주세요.");
		}
	}

	/** 소수 자릿수 표현이 달라도 실제 금융값이 같은지 비교합니다. */
	private boolean sameDecimal(BigDecimal left, BigDecimal right) {
		return left == null ? right == null : right != null && left.compareTo(right) == 0;
	}

	/** 승인 상태이고 유효시간이 지나지 않은 미리보기인지 검사합니다. */
	private void validateExecutablePreview(
			OrderCancellationPreviewResponse preview, OffsetDateTime now) {
		if (preview.status() != OrderCancellationPreviewStatus.APPROVED) {
			throw new OrderPreviewStateException("승인된 취소 미리보기만 실행할 수 있습니다.");
		}
		if (!preview.expiresAt().isAfter(now)) {
			throw new OrderPreviewExpiredException(
					"취소 미리보기의 실행 시간이 지났습니다. 새 미리보기를 만들어 주세요.");
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

	/** 저장소에서 취소 미리보기를 찾거나 찾을 수 없음 오류를 발생시킵니다. */
	private OrderCancellationPreviewResponse findPreview(String previewId) {
		return previewStore.findById(previewId)
				.orElseThrow(() -> new OrderPreviewNotFoundException("취소 미리보기를 찾을 수 없습니다."));
	}

	/** 저장소에서 취소 실행을 찾거나 내부 저장 오류를 발생시킵니다. */
	private OrderCancellationExecutionResponse findExecution(String executionId) {
		return executionStore.findById(executionId)
				.orElseThrow(() -> new OrderExecutionSubmissionException("저장된 취소 실행 결과를 찾지 못했습니다."));
	}

	/** 검증된 체결 수량과 계산한 잔여 수량을 함께 전달합니다. */
	private record CancellationAmounts(BigDecimal filledQuantity, BigDecimal remainingQuantity) {
	}
}
