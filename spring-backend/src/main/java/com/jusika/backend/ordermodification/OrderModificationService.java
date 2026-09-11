package com.jusika.backend.ordermodification;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.jusika.backend.brokersafety.BrokerOrderRiskSnapshot;
import com.jusika.backend.order.OrderModificationSubmissionRequest;
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
import com.jusika.backend.orderpreview.OrderType;
import com.jusika.backend.stock.StockPriceResponse;
import com.jusika.backend.toss.market.TossPriceClient;
import com.jusika.backend.toss.orderhistory.TossOrderHistoryClient;

/** 정정 대상과 요청 내용을 고정하고 승인·재검증 뒤 중복 없는 모의 정정을 실행합니다. */
@Service
public class OrderModificationService {
	private static final BigDecimal HIGH_VALUE_KRW_THRESHOLD = new BigDecimal("100000000");
	private static final BigDecimal MAX_KRW_ORDER_AMOUNT = new BigDecimal("3000000000");

	private final TossOrderHistoryClient historyClient;
	private final TossPriceClient priceClient;
	private final OrderModificationPreviewStore previewStore;
	private final OrderModificationExecutionStore executionStore;
	private final OrderModificationGateway modificationGateway;
	private final OrderPreviewProperties properties;
	private final Clock clock;

	/** 정정 대상 조회, 현재가, 상태 저장, 실행 경계와 승인 유효시간을 전달받습니다. */
	public OrderModificationService(
			TossOrderHistoryClient historyClient,
			TossPriceClient priceClient,
			OrderModificationPreviewStore previewStore,
			OrderModificationExecutionStore executionStore,
			OrderModificationGateway modificationGateway,
			OrderPreviewProperties properties,
			Clock clock) {
		this.historyClient = historyClient;
		this.priceClient = priceClient;
		this.previewStore = previewStore;
		this.executionStore = executionStore;
		this.modificationGateway = modificationGateway;
		this.properties = properties;
		this.clock = clock;
	}

	/** 원주문과 요청한 정정값을 검증하고 사용자가 승인할 변경 불가 사본을 저장합니다. */
	public OrderModificationPreviewResponse createPreview(OrderModificationPreviewRequest request) {
		validateBasicRequest(request);
		OrderDetailResponse order = historyClient.getOrder(request.accountSeq(), request.orderId());
		validateOrderIdentity(order, request.accountSeq(), request.orderId());
		BigDecimal filled = validateModifiableOrder(order);
		ModificationCalculation calculation = validateModification(
				order, filled, request.orderType(), request.quantity(), request.price());
		validateActuallyChanged(order, request);

		OffsetDateTime createdAt = OffsetDateTime.now(clock);
		return previewStore.save(new OrderModificationPreviewResponse(
				UUID.randomUUID().toString(), createdAt, createdAt.plus(properties.expiration()),
				order.accountSeq(), order.orderId(), order.symbol(), order.side(),
				order.orderTypeCode(), order.timeInForceCode(), order.price(), order.quantity(),
				order.orderAmount(), filled, order.currency(), request.orderType(), request.quantity(),
				request.price(), calculation.referencePrice(), calculation.estimatedOrderAmount(),
				calculation.requiresHighValueConfirmation(),
				OrderModificationPreviewStatus.PENDING_APPROVAL, null));
	}

	/** 유효시간 안의 승인 대기 정정 미리보기만 한 번 승인합니다. */
	public OrderModificationPreviewResponse approvePreview(String previewId) {
		validateUuid(previewId, "정정 미리보기");
		OffsetDateTime approvedAt = OffsetDateTime.now(clock);
		previewStore.expirePending(previewId, approvedAt);
		if (previewStore.approvePending(previewId, approvedAt)) return findPreview(previewId);
		OrderModificationPreviewResponse preview = findPreview(previewId);
		switch (preview.status()) {
			case EXPIRED -> throw new OrderPreviewExpiredException(
					"정정 미리보기의 승인 시간이 지났습니다. 새 미리보기를 만들어 주세요.");
			case APPROVED -> throw new OrderPreviewStateException("이미 승인한 정정 미리보기입니다.");
			case CONSUMED -> throw new OrderPreviewStateException("이미 정정에 사용한 미리보기입니다.");
			case PENDING_APPROVAL -> throw new OrderPreviewStateException(
					"정정 미리보기 상태가 변경되어 승인하지 못했습니다.");
		}
		throw new IllegalStateException("처리할 수 없는 정정 미리보기 상태입니다.");
	}

	/** 승인된 정정과 현재 원주문을 재검증하고 원주문당 한 번만 현재 모드로 실행합니다. */
	public OrderModificationExecutionResponse executeApprovedPreview(String previewId) {
		validateUuid(previewId, "정정 미리보기");
		OrderModificationPreviewResponse preview = findPreview(previewId);
		OffsetDateTime startedAt = OffsetDateTime.now(clock);
		validateExecutablePreview(preview, startedAt);
		modificationGateway.requireModificationAvailable(preview.accountSeq());
		modificationGateway.requireInstrumentAllowed(preview.symbol(), preview.currency());

		OrderDetailResponse current = historyClient.getOrder(
				preview.accountSeq(), preview.originalOrderId());
		validateOrderIdentity(current, preview.accountSeq(), preview.originalOrderId());
		validateUnchangedOriginal(preview, current);
		BigDecimal filled = validateModifiableOrder(current);
		ModificationCalculation calculation = validateModification(
				current, filled, preview.requestedOrderType(),
				preview.requestedQuantity(), preview.requestedPrice());
		if (calculation.requiresHighValueConfirmation()
				&& !preview.requiresHighValueConfirmation()) {
			throw new OrderExecutionConflictException(
					"시장가 기준 금액이 1억원 이상으로 변경되었습니다. 새 정정 미리보기를 만들어 주세요.");
		}
		BrokerOrderRiskSnapshot riskSnapshot = new BrokerOrderRiskSnapshot(
				calculation.calculationQuantity(),
				calculation.estimatedOrderAmount(),
				preview.currency());
		modificationGateway.requireOrderWithinLimits(riskSnapshot);
		modificationGateway.requireDailyOrderWithinLimits(preview.accountSeq(), riskSnapshot);

		String executionId = UUID.randomUUID().toString();
		OrderModificationExecutionResponse prepared = new OrderModificationExecutionResponse(
				executionId, preview.previewId(), preview.originalOrderId(), null,
				modificationGateway.mode(), OrderExecutionStatus.PREPARED, null,
				startedAt, startedAt, null, null);
		if (!executionStore.claim(prepared)) {
			throw new OrderExecutionConflictException("이미 정정했거나 정정 중인 원주문입니다.");
		}
		if (!previewStore.consumeApproved(previewId, startedAt)) {
			executionStore.markPreparationFailed(executionId, startedAt);
			throw new OrderExecutionConflictException("정정 미리보기의 승인 상태가 변경되었습니다.");
		}
		if (!executionStore.markSubmitting(executionId, startedAt)) {
			executionStore.markPreparationFailed(executionId, startedAt);
			throw new OrderExecutionSubmissionException("정정 실행 상태를 제출 중으로 변경하지 못했습니다.");
		}
		OrderModificationSubmissionRequest submission = new OrderModificationSubmissionRequest(
				preview.currency(), preview.requestedOrderType(), preview.requestedQuantity(),
				preview.requestedPrice(), preview.requiresHighValueConfirmation(),
				riskSnapshot, preview.symbol());
		return modifyAndRecord(executionId, preview.accountSeq(), preview.originalOrderId(), submission);
	}

	/** 실행 식별값으로 저장된 정정 실행 상태를 조회합니다. */
	public OrderModificationExecutionResponse getExecution(String executionId) {
		validateUuid(executionId, "정정 실행");
		return executionStore.findById(executionId)
				.orElseThrow(() -> new OrderExecutionNotFoundException("정정 실행 기록을 찾을 수 없습니다."));
	}

	/** 정정 게이트웨이 결과를 접수·거절·결과 불명 상태로 나눠 저장합니다. */
	private OrderModificationExecutionResponse modifyAndRecord(
			String executionId, long accountSeq, String originalOrderId,
			OrderModificationSubmissionRequest request) {
		try {
			OrderOperationResponse result = modificationGateway.modifyOrder(
					accountSeq, originalOrderId, request);
			if (result == null || result.orderId() == null || result.orderId().isBlank()
					|| originalOrderId.equals(result.orderId())) {
				throw new OrderSubmissionException("주문 정정 응답 형식이 올바르지 않습니다.", true);
			}
			OffsetDateTime completedAt = OffsetDateTime.now(clock);
			if (!executionStore.markAccepted(executionId, result.orderId(), completedAt)) {
				throw new OrderExecutionSubmissionException("정정 접수 결과를 데이터베이스에 기록하지 못했습니다.");
			}
			return findExecution(executionId);
		} catch (OrderSubmissionException exception) {
			OffsetDateTime failedAt = OffsetDateTime.now(clock);
			if (exception.isSubmissionStateUnknown()) {
				executionStore.markUnknown(executionId, failedAt);
				throw new OrderExecutionSubmissionException(
						"주문 정정 접수 여부를 확인할 수 없습니다. 자동으로 다시 정정하지 마세요.");
			}
			executionStore.markRejected(executionId, failedAt);
			throw new OrderExecutionSubmissionException("증권사가 주문 정정을 거절했습니다.");
		} catch (OrderExecutionSubmissionException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			executionStore.markUnknown(executionId, OffsetDateTime.now(clock));
			throw new OrderExecutionSubmissionException(
					"주문 정정 접수 여부를 확인할 수 없습니다. 자동으로 다시 정정하지 마세요.");
		}
	}

	/** 외부 조회 전에 계좌·원주문·정정 유형의 기본 형식을 검사합니다. */
	private void validateBasicRequest(OrderModificationPreviewRequest request) {
		if (request == null) throw new OrderPreviewException("정정 미리보기 요청이 필요합니다.");
		if (request.accountSeq() <= 0) throw new OrderPreviewException("계좌 식별값은 1 이상이어야 합니다.");
		validateOrderId(request.orderId());
		if (request.orderType() == null) {
			throw new OrderPreviewException("정정할 주문 유형은 LIMIT 또는 MARKET이어야 합니다.");
		}
	}

	/** 주문 식별값의 길이와 공백·제어문자 포함 여부를 검사합니다. */
	private void validateOrderId(String orderId) {
		if (orderId == null || orderId.isBlank() || orderId.length() > 512
				|| orderId.chars().anyMatch(Character::isWhitespace)
				|| orderId.chars().anyMatch(Character::isISOControl)) {
			throw new OrderPreviewException("주문 식별값 형식이 올바르지 않습니다.");
		}
	}

	/** 조회 응답이 요청한 계좌·원주문이며 정정 판단 필드가 모두 있는지 검사합니다. */
	private void validateOrderIdentity(OrderDetailResponse order, long accountSeq, String orderId) {
		if (order == null || order.accountSeq() != accountSeq || !orderId.equals(order.orderId())) {
			throw new OrderPreviewException("조회한 주문이 정정 요청 대상과 일치하지 않습니다.");
		}
		if (order.symbol() == null || order.symbol().isBlank() || order.side() == null
				|| order.orderTypeCode() == null || order.orderTypeCode().isBlank()
				|| order.timeInForceCode() == null || order.timeInForceCode().isBlank()
				|| order.status() == null || order.currency() == null) {
			throw new OrderPreviewException("정정 판단에 필요한 원주문 정보가 부족합니다.");
		}
	}

	/** 미체결 또는 일부 체결 상태와 누적 체결 수량을 검증합니다. */
	private BigDecimal validateModifiableOrder(OrderDetailResponse order) {
		if (order.status() != OrderStatus.PENDING && order.status() != OrderStatus.PARTIAL_FILLED) {
			throw new OrderPreviewException("현재 상태에서는 주문을 정정할 수 없습니다.");
		}
		BigDecimal filled = order.execution() == null ? null : order.execution().filledQuantity();
		if (filled == null || filled.signum() < 0) {
			throw new OrderPreviewException("누적 체결 수량이 올바르지 않습니다.");
		}
		return filled;
	}

	/** 국내·미국별 정정 수량과 가격 규칙, 고액·최대 금액 규칙을 검사합니다. */
	private ModificationCalculation validateModification(
			OrderDetailResponse order, BigDecimal filled, OrderType orderType,
			BigDecimal quantity, BigDecimal price) {
		if (orderType == null) throw new OrderPreviewException("정정할 주문 유형이 필요합니다.");
		BigDecimal calculationQuantity;
		if ("KRW".equals(order.currency())) {
			validatePositiveDecimal(quantity, "국내 주식 정정 수량");
			if (normalizedScale(quantity) > 0) {
				throw new OrderPreviewException("국내 주식 정정 수량은 정수여야 합니다.");
			}
			if (quantity.compareTo(filled) <= 0) {
				throw new OrderPreviewException("정정 수량은 이미 체결된 수량보다 커야 합니다.");
			}
			calculationQuantity = quantity;
		} else if ("USD".equals(order.currency())) {
			if (quantity != null) {
				throw new OrderPreviewException("미국 주식 주문 정정은 가격만 변경할 수 있습니다.");
			}
			calculationQuantity = order.quantity();
		} else {
			throw new OrderPreviewException("지원하지 않는 정정 주문 통화입니다.");
		}

		BigDecimal referencePrice = null;
		BigDecimal calculationPrice;
		if (orderType == OrderType.LIMIT) {
			validatePositiveDecimal(price, "정정 지정가");
			validatePriceScale(price, order.currency());
			calculationPrice = price;
		} else {
			if (price != null) throw new OrderPreviewException("시장가 정정에는 가격을 입력할 수 없습니다.");
			StockPriceResponse currentPrice = priceClient.getCurrentPrice(order.symbol());
			if (currentPrice == null || !order.symbol().equals(currentPrice.symbol())
					|| !order.currency().equals(currentPrice.currency())
					|| currentPrice.price() == null || currentPrice.price().signum() <= 0) {
				throw new OrderPreviewException("시장가 정정 계산에 사용할 현재가가 올바르지 않습니다.");
			}
			referencePrice = currentPrice.price();
			calculationPrice = referencePrice;
		}

		BigDecimal estimated = calculationQuantity == null
				? null : calculationPrice.multiply(calculationQuantity);
		boolean requiresHighValueConfirmation = false;
		if ("KRW".equals(order.currency()) && estimated != null) {
			if (estimated.compareTo(MAX_KRW_ORDER_AMOUNT) > 0) {
				throw new OrderPreviewException("정정 후 국내 주문금액은 30억원을 초과할 수 없습니다.");
			}
			requiresHighValueConfirmation = estimated.compareTo(HIGH_VALUE_KRW_THRESHOLD) >= 0;
		}
		return new ModificationCalculation(
				referencePrice, calculationQuantity, estimated, requiresHighValueConfirmation);
	}

	/** 정정 결과가 원주문과 완전히 같아 불필요한 요청인지 검사합니다. */
	private void validateActuallyChanged(
			OrderDetailResponse order, OrderModificationPreviewRequest request) {
		boolean sameType = request.orderType().name().equals(order.orderTypeCode());
		boolean sameQuantity = "USD".equals(order.currency())
				|| sameDecimal(request.quantity(), order.quantity());
		boolean samePrice = sameDecimal(request.price(), order.price());
		if (sameType && sameQuantity && samePrice) {
			throw new OrderPreviewException("원주문과 다른 주문 유형, 수량 또는 가격을 입력해 주세요.");
		}
	}

	/** 승인한 원주문의 변경 불가 값이 실행 직전에도 모두 같은지 검사합니다. */
	private void validateUnchangedOriginal(
			OrderModificationPreviewResponse preview, OrderDetailResponse current) {
		if (!Objects.equals(preview.symbol(), current.symbol()) || preview.side() != current.side()
				|| !Objects.equals(preview.originalOrderTypeCode(), current.orderTypeCode())
				|| !Objects.equals(preview.originalTimeInForceCode(), current.timeInForceCode())
				|| !Objects.equals(preview.currency(), current.currency())
				|| !sameDecimal(preview.originalPrice(), current.price())
				|| !sameDecimal(preview.originalQuantity(), current.quantity())
				|| !sameDecimal(preview.originalOrderAmount(), current.orderAmount())) {
			throw new OrderExecutionConflictException(
					"승인 뒤 원주문 내용이 변경되었습니다. 새 정정 미리보기를 만들어 주세요.");
		}
	}

	/** 금융 소수의 표현 자릿수가 달라도 실제 값이 같은지 비교합니다. */
	private boolean sameDecimal(BigDecimal left, BigDecimal right) {
		return left == null ? right == null : right != null && left.compareTo(right) == 0;
	}

	/** 금융값이 양수이고 외부 API 최대 문자열 길이를 넘지 않는지 검사합니다. */
	private void validatePositiveDecimal(BigDecimal value, String name) {
		if (value == null || value.signum() <= 0) throw new OrderPreviewException(name + "은 0보다 커야 합니다.");
		if (value.toPlainString().length() > 30) throw new OrderPreviewException(name + "은 30자 이하여야 합니다.");
	}

	/** 국내 원 단위와 미국 달러 가격 소수 자릿수 규칙을 검사합니다. */
	private void validatePriceScale(BigDecimal price, String currency) {
		int scale = normalizedScale(price);
		if ("KRW".equals(currency) && scale > 0) {
			throw new OrderPreviewException("국내 주식 정정 지정가는 원 단위 정수여야 합니다.");
		}
		if ("USD".equals(currency)) {
			int max = price.compareTo(BigDecimal.ONE) < 0 ? 4 : 2;
			if (scale > max) throw new OrderPreviewException("미국 주식 정정 지정가의 소수 자릿수가 너무 많습니다.");
		}
	}

	/** 값 끝의 불필요한 0을 제외한 실제 소수 자릿수를 반환합니다. */
	private int normalizedScale(BigDecimal value) { return Math.max(value.stripTrailingZeros().scale(), 0); }

	/** 승인 상태이며 유효시간이 남은 미리보기인지 검사합니다. */
	private void validateExecutablePreview(OrderModificationPreviewResponse preview, OffsetDateTime now) {
		if (preview.status() != OrderModificationPreviewStatus.APPROVED) {
			throw new OrderPreviewStateException("승인된 정정 미리보기만 실행할 수 있습니다.");
		}
		if (!preview.expiresAt().isAfter(now)) {
			throw new OrderPreviewExpiredException("정정 미리보기의 실행 시간이 지났습니다.");
		}
	}

	/** HTTP 경로로 받은 식별값이 표준 UUID인지 검사합니다. */
	private void validateUuid(String value, String name) {
		if (value == null) throw new OrderPreviewException(name + " 식별값이 필요합니다.");
		try { UUID.fromString(value); }
		catch (IllegalArgumentException exception) {
			throw new OrderPreviewException(name + " 식별값 형식이 올바르지 않습니다.");
		}
	}

	/** 저장된 정정 미리보기를 찾거나 찾을 수 없음 오류를 발생시킵니다. */
	private OrderModificationPreviewResponse findPreview(String id) {
		return previewStore.findById(id)
				.orElseThrow(() -> new OrderPreviewNotFoundException("정정 미리보기를 찾을 수 없습니다."));
	}

	/** 저장된 정정 실행을 찾거나 내부 저장 오류를 발생시킵니다. */
	private OrderModificationExecutionResponse findExecution(String id) {
		return executionStore.findById(id)
				.orElseThrow(() -> new OrderExecutionSubmissionException("저장된 정정 실행 결과를 찾지 못했습니다."));
	}

	/** 시장가 참조 가격과 계산된 예상 주문금액을 함께 전달합니다. */
	private record ModificationCalculation(
			BigDecimal referencePrice,
			BigDecimal calculationQuantity,
			BigDecimal estimatedOrderAmount,
			boolean requiresHighValueConfirmation) {
	}
}
