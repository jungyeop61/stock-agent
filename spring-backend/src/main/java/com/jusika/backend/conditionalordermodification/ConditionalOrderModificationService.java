package com.jusika.backend.conditionalordermodification;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.jusika.backend.brokersafety.BrokerOrderRiskSnapshot;
import com.jusika.backend.buyingpower.BuyingPowerResponse;
import com.jusika.backend.commission.CommissionsResponse;
import com.jusika.backend.commission.CommissionsResponse.CommissionItem;
import com.jusika.backend.conditionalorder.ConditionalOrderConditionStatus;
import com.jusika.backend.conditionalorder.ConditionalOrderDetailResponse;
import com.jusika.backend.conditionalorder.ConditionalOrderDetailResponse.Condition;
import com.jusika.backend.conditionalorder.ConditionalOrderModificationResponse;
import com.jusika.backend.conditionalorder.ConditionalOrderModificationSubmissionRequest;
import com.jusika.backend.conditionalorder.ConditionalOrderStatus;
import com.jusika.backend.conditionalorder.ConditionalOrderType;
import com.jusika.backend.conditionalordermodification.ConditionalOrderModificationPreviewRequest;
import com.jusika.backend.conditionalordermodification.ConditionalOrderModificationPreviewResponse.OriginalCondition;
import com.jusika.backend.conditionalordermodification.ConditionalOrderModificationPreviewResponse.RequestedCondition;
import com.jusika.backend.orderexecution.OrderExecutionConflictException;
import com.jusika.backend.orderexecution.OrderExecutionNotFoundException;
import com.jusika.backend.orderexecution.OrderExecutionStatus;
import com.jusika.backend.orderexecution.OrderExecutionSubmissionException;
import com.jusika.backend.orderexecution.OrderExecutionValidationException;
import com.jusika.backend.orderexecution.OrderSubmissionException;
import com.jusika.backend.orderpreview.OrderPreviewException;
import com.jusika.backend.orderpreview.OrderPreviewExpiredException;
import com.jusika.backend.orderpreview.OrderPreviewNotFoundException;
import com.jusika.backend.orderpreview.OrderPreviewProperties;
import com.jusika.backend.orderpreview.OrderPreviewStateException;
import com.jusika.backend.orderpreview.OrderSide;
import com.jusika.backend.orderpreview.OrderType;
import com.jusika.backend.sellablequantity.SellableQuantityResponse;
import com.jusika.backend.stock.StockPriceResponse;
import com.jusika.backend.toss.conditionalorder.TossConditionalOrderClient;
import com.jusika.backend.toss.market.TossPriceClient;
import com.jusika.backend.toss.orderinfo.TossBuyingPowerClient;
import com.jusika.backend.toss.orderinfo.TossCommissionsClient;
import com.jusika.backend.toss.orderinfo.TossSellableQuantityClient;

/** 원조건 주문과 새 전체 구성을 승인·재검증해 중복 없는 모의 정정을 실행합니다. */
@Service
public class ConditionalOrderModificationService {

	private static final BigDecimal HIGH_VALUE_KRW_THRESHOLD = new BigDecimal("100000000");
	private static final BigDecimal MAX_KRW_ORDER_AMOUNT = new BigDecimal("3000000000");
	private static final BigDecimal MAX_COMMISSION_RATE = BigDecimal.ONE;
	private static final Set<ConditionalOrderStatus> MODIFIABLE_STATUSES = Set.of(
			ConditionalOrderStatus.WATCHING, ConditionalOrderStatus.PAUSED);
	private static final Set<ConditionalOrderConditionStatus> MODIFIABLE_CONDITION_STATUSES = Set.of(
			ConditionalOrderConditionStatus.WATCHING,
			ConditionalOrderConditionStatus.HOLDING,
			ConditionalOrderConditionStatus.PAUSED);

	private final TossConditionalOrderClient conditionalOrderClient;
	private final TossPriceClient priceClient;
	private final TossBuyingPowerClient buyingPowerClient;
	private final TossSellableQuantityClient sellableQuantityClient;
	private final TossCommissionsClient commissionsClient;
	private final ConditionalOrderModificationPreviewStore previewStore;
	private final ConditionalOrderModificationExecutionStore executionStore;
	private final ConditionalOrderModificationGateway modificationGateway;
	private final OrderPreviewProperties properties;
	private final Clock clock;

	/** 조건 주문과 계좌 정보 조회, 저장소, 실행 경계와 승인 시간을 전달받습니다. */
	public ConditionalOrderModificationService(
			TossConditionalOrderClient conditionalOrderClient,
			TossPriceClient priceClient,
			TossBuyingPowerClient buyingPowerClient,
			TossSellableQuantityClient sellableQuantityClient,
			TossCommissionsClient commissionsClient,
			ConditionalOrderModificationPreviewStore previewStore,
			ConditionalOrderModificationExecutionStore executionStore,
			ConditionalOrderModificationGateway modificationGateway,
			OrderPreviewProperties properties,
			Clock clock) {
		this.conditionalOrderClient = conditionalOrderClient;
		this.priceClient = priceClient;
		this.buyingPowerClient = buyingPowerClient;
		this.sellableQuantityClient = sellableQuantityClient;
		this.commissionsClient = commissionsClient;
		this.previewStore = previewStore;
		this.executionStore = executionStore;
		this.modificationGateway = modificationGateway;
		this.properties = properties;
		this.clock = clock;
	}

	/** 최신 원조건 주문과 새 전체 구성을 검증해 승인용 변경 불가 사본을 저장합니다. */
	public ConditionalOrderModificationPreviewResponse createPreview(
			ConditionalOrderModificationPreviewRequest request) {
		validateBasicRequest(request, false);
		ConditionalOrderDetailResponse original = conditionalOrderClient.getConditionalOrder(
				request.accountSeq(), request.conditionalOrderId());
		validateOriginalIdentity(original, request.accountSeq(), request.conditionalOrderId(), false);
		validateModifiableOriginal(original, false);
		StockPriceResponse stockPrice = priceClient.getCurrentPrice(original.symbol());
		Market market = resolveMarket(original, stockPrice, false);
		ValidationResult result = validateRequestedOrder(request.accountSeq(), original.symbol(),
				request.type(), request.quantity(), request.orderType(), request.expireDate(),
				toRequested(request.first()), toRequested(request.second()), stockPrice.price(),
				market, false);
		validateActuallyChanged(original, request);

		OffsetDateTime createdAt = OffsetDateTime.now(clock);
		return previewStore.save(new ConditionalOrderModificationPreviewResponse(
				UUID.randomUUID().toString(), createdAt, createdAt.plus(properties.expiration()),
				original.accountSeq(), original.conditionalOrderId(), original.type(), original.status(),
				original.symbol(), original.market(), original.quantity(), original.orderType(),
				original.expireDate(), snapshot(original.first()), snapshot(original.second()),
				original.createdAt(), request.type(), request.quantity(), request.orderType(),
				request.expireDate(), toRequested(request.first()), toRequested(request.second()),
				stockPrice.price(), market.currency(), result.requiresHighValueConfirmation(),
				ConditionalOrderModificationPreviewStatus.PENDING_APPROVAL, null));
	}

	/** 유효시간 안의 승인 대기 조건 주문 정정 미리보기만 한 번 승인합니다. */
	public ConditionalOrderModificationPreviewResponse approvePreview(String previewId) {
		validateUuid(previewId, "조건 주문 정정 미리보기");
		OffsetDateTime approvedAt = OffsetDateTime.now(clock);
		previewStore.expirePending(previewId, approvedAt);
		if (previewStore.approvePending(previewId, approvedAt)) return findPreview(previewId);
		ConditionalOrderModificationPreviewResponse preview = findPreview(previewId);
		switch (preview.status()) {
			case EXPIRED -> throw new OrderPreviewExpiredException(
					"조건 주문 정정 미리보기의 승인 시간이 지났습니다. 새 미리보기를 만들어 주세요.");
			case APPROVED -> throw new OrderPreviewStateException(
					"이미 승인한 조건 주문 정정 미리보기입니다.");
			case CONSUMED -> throw new OrderPreviewStateException(
					"이미 정정에 사용한 조건 주문 미리보기입니다.");
			case PENDING_APPROVAL -> throw new OrderPreviewStateException(
					"조건 주문 정정 미리보기 상태가 변경되어 승인하지 못했습니다.");
		}
		throw new IllegalStateException("처리할 수 없는 조건 주문 정정 미리보기 상태입니다.");
	}

	/** 승인 사본과 최신 원주문을 비교하고 새 전체 조건을 재검증한 뒤 한 번만 모의 정정합니다. */
	public ConditionalOrderModificationExecutionResponse executeApprovedPreview(String previewId) {
		validateUuid(previewId, "조건 주문 정정 미리보기");
		ConditionalOrderModificationPreviewResponse preview = findPreview(previewId);
		OffsetDateTime startedAt = OffsetDateTime.now(clock);
		validateExecutablePreview(preview, startedAt);
		modificationGateway.requireModificationAvailable(preview.accountSeq());
		modificationGateway.requireInstrumentAllowed(preview.symbol(), preview.currency());

		ConditionalOrderDetailResponse current = conditionalOrderClient.getConditionalOrder(
				preview.accountSeq(), preview.originalConditionalOrderId());
		validateOriginalIdentity(current, preview.accountSeq(),
				preview.originalConditionalOrderId(), true);
		validateUnchangedOriginal(preview, current);
		validateModifiableOriginal(current, true);
		StockPriceResponse stockPrice = priceClient.getCurrentPrice(preview.symbol());
		Market market = resolveMarket(current, stockPrice, true);
		ValidationResult result = validateRequestedOrder(preview.accountSeq(), preview.symbol(),
				preview.requestedType(), preview.requestedQuantity(), preview.requestedOrderType(),
				preview.requestedExpireDate(), preview.requestedFirst(), preview.requestedSecond(),
				stockPrice.price(), market, true);
		if (result.requiresHighValueConfirmation()
				&& !preview.requiresHighValueConfirmation()) {
			throw new OrderExecutionConflictException(
					"실행 직전 국내 주문금액이 1억원 이상이 되었습니다. 새 정정 미리보기를 만들어 주세요.");
		}
		BrokerOrderRiskSnapshot riskSnapshot = new BrokerOrderRiskSnapshot(
				preview.requestedQuantity(), result.maximumOrderAmount(), market.currency());
		modificationGateway.requireOrderWithinLimits(riskSnapshot);
		modificationGateway.requireDailyOrderWithinLimits(preview.accountSeq(), riskSnapshot);

		String executionId = UUID.randomUUID().toString();
		ConditionalOrderModificationExecutionResponse prepared =
				new ConditionalOrderModificationExecutionResponse(
						executionId, preview.previewId(), preview.accountSeq(),
						preview.originalConditionalOrderId(), null, modificationGateway.mode(),
						OrderExecutionStatus.PREPARED, null, startedAt, startedAt, null, null);
		if (!executionStore.claim(prepared)) {
			throw new OrderExecutionConflictException(
					"이미 정정했거나 정정 중인 조건 주문입니다.");
		}
		if (!previewStore.consumeApproved(previewId, startedAt)) {
			executionStore.markPreparationFailed(executionId, startedAt);
			throw new OrderExecutionConflictException(
					"조건 주문 정정 미리보기의 승인 상태가 변경되었습니다.");
		}
		if (!executionStore.markSubmitting(executionId, startedAt)) {
			executionStore.markPreparationFailed(executionId, startedAt);
			throw new OrderExecutionSubmissionException(
					"조건 주문 정정 실행 상태를 제출 중으로 변경하지 못했습니다.");
		}
		return modifyAndRecord(executionId, preview, riskSnapshot);
	}

	/** 실행 식별값으로 저장된 조건 주문 정정 결과를 조회합니다. */
	public ConditionalOrderModificationExecutionResponse getExecution(String executionId) {
		validateUuid(executionId, "조건 주문 정정 실행");
		return executionStore.findById(executionId)
				.orElseThrow(() -> new OrderExecutionNotFoundException(
						"조건 주문 정정 실행 기록을 찾을 수 없습니다."));
	}

	/** MOCK 정정 결과를 성공·확정 거절·결과 불명으로 나눠 저장합니다. */
	private ConditionalOrderModificationExecutionResponse modifyAndRecord(
			String executionId,
			ConditionalOrderModificationPreviewResponse preview,
			BrokerOrderRiskSnapshot riskSnapshot) {
		ConditionalOrderModificationSubmissionRequest request = toSubmissionRequest(
				preview, riskSnapshot);
		try {
			ConditionalOrderModificationResponse response = modificationGateway.modify(
					preview.accountSeq(), preview.originalConditionalOrderId(), request);
			validateModificationResponse(response, preview.originalConditionalOrderId());
			OffsetDateTime completedAt = OffsetDateTime.now(clock);
			if (!executionStore.markAccepted(
					executionId, response.conditionalOrderId(), completedAt)) {
				throw new OrderExecutionSubmissionException(
						"조건 주문 정정 성공 결과를 데이터베이스에 기록하지 못했습니다.");
			}
			return findExecution(executionId);
		} catch (OrderSubmissionException exception) {
			OffsetDateTime failedAt = OffsetDateTime.now(clock);
			if (exception.isSubmissionStateUnknown()) {
				executionStore.markUnknown(executionId, failedAt);
				throw new OrderExecutionSubmissionException(
						"조건 주문 정정 여부를 확인할 수 없습니다. 자동으로 다시 정정하지 마세요.");
			}
			executionStore.markRejected(executionId, failedAt);
			throw new OrderExecutionSubmissionException("증권사가 조건 주문 정정을 거절했습니다.");
		} catch (OrderExecutionSubmissionException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			executionStore.markUnknown(executionId, OffsetDateTime.now(clock));
			throw new OrderExecutionSubmissionException(
					"조건 주문 정정 여부를 확인할 수 없습니다. 자동으로 다시 정정하지 마세요.");
		}
	}

	/** 외부 조회 전에 계좌·식별값·새 전체 구성의 필수값을 검사합니다. */
	private void validateBasicRequest(
			ConditionalOrderModificationPreviewRequest request, boolean finalValidation) {
		if (request == null) throw validationException("조건 주문 정정 미리보기 요청이 필요합니다.", finalValidation);
		if (request.accountSeq() <= 0) throw validationException("계좌 식별값은 1 이상이어야 합니다.", finalValidation);
		validateConditionalOrderId(request.conditionalOrderId(), finalValidation);
		if (request.type() == null) throw validationException("정정 후 조건 주문 유형이 필요합니다.", finalValidation);
		if (request.orderType() == null) throw validationException("정정 후 주문 유형이 필요합니다.", finalValidation);
		if (request.quantity() == null || request.quantity().signum() <= 0
				|| request.quantity().toPlainString().length() > 30) {
			throw validationException("정정 수량은 0보다 크고 30자 이하여야 합니다.", finalValidation);
		}
		if (request.expireDate() == null || request.expireDate().isBefore(LocalDate.now(clock))) {
			throw validationException("정정 후 만료일은 오늘 또는 이후 날짜여야 합니다.", finalValidation);
		}
		if (request.first() == null) throw validationException("정정 후 첫 번째 조건이 필요합니다.", finalValidation);
		if ((request.type() == ConditionalOrderType.SINGLE && request.second() != null)
				|| (request.type() != ConditionalOrderType.SINGLE && request.second() == null)) {
			throw validationException("정정 후 유형과 감시 조건 개수가 일치하지 않습니다.", finalValidation);
		}
	}

	/** 조건 주문 식별값이 경로를 오염시키지 않는 불투명 문자열인지 검사합니다. */
	private void validateConditionalOrderId(String value, boolean finalValidation) {
		if (value == null || value.isBlank() || value.length() > 512
				|| value.codePoints().anyMatch(codePoint -> Character.isWhitespace(codePoint)
						|| codePoint < 32 || codePoint == 127)) {
			throw validationException("조건 주문 식별값 형식이 올바르지 않습니다.", finalValidation);
		}
	}

	/** 조회한 원조건 주문의 대상 일치와 필수 구조를 검사합니다. */
	private void validateOriginalIdentity(
			ConditionalOrderDetailResponse order, long accountSeq, String conditionalOrderId,
			boolean finalValidation) {
		if (order == null || order.accountSeq() != accountSeq
				|| !conditionalOrderId.equals(order.conditionalOrderId())) {
			throw validationException("조회한 조건 주문이 정정 대상과 일치하지 않습니다.", finalValidation);
		}
		if (order.type() == null || order.status() == null || order.symbol() == null
				|| order.symbol().isBlank() || order.market() == null || order.quantity() == null
				|| order.quantity().signum() <= 0 || order.orderType() == null
				|| order.first() == null || order.createdAt() == null) {
			throw validationException("조건 주문 정정 판단에 필요한 원주문 정보가 부족합니다.", finalValidation);
		}
		if ((order.type() == ConditionalOrderType.SINGLE && order.second() != null)
				|| (order.type() != ConditionalOrderType.SINGLE && order.second() == null)) {
			throw validationException("원조건 주문 유형과 감시 조건 구성이 일치하지 않습니다.", finalValidation);
		}
		validateOriginalConditionShape(order.first(), finalValidation);
		if (order.second() != null) validateOriginalConditionShape(order.second(), finalValidation);
	}

	/** 기존 한 감시 조건에 상태와 유형이 있는지 확인합니다. */
	private void validateOriginalConditionShape(Condition condition, boolean finalValidation) {
		if (condition.type() == null || condition.status() == null) {
			throw validationException("원조건 주문의 감시 조건 정보가 부족합니다.", finalValidation);
		}
	}

	/** 아직 일반 주문이 생성되지 않은 감시·대기·일시중지 원주문만 허용합니다. */
	private void validateModifiableOriginal(
			ConditionalOrderDetailResponse order, boolean finalValidation) {
		if (!MODIFIABLE_STATUSES.contains(order.status())) {
			throw validationException("이미 발동했거나 종료된 조건 주문은 정정할 수 없습니다.", finalValidation);
		}
		validateModifiableCondition(order.first(), finalValidation);
		if (order.second() != null) validateModifiableCondition(order.second(), finalValidation);
	}

	/** 기존 한 감시 조건이 발동 전의 변경 가능 상태인지 확인합니다. */
	private void validateModifiableCondition(Condition condition, boolean finalValidation) {
		if (!MODIFIABLE_CONDITION_STATUSES.contains(condition.status())
				|| (condition.triggeredOrderId() != null && !condition.triggeredOrderId().isBlank())) {
			throw validationException("이미 발동한 감시 조건이 있어 조건 주문을 정정할 수 없습니다.", finalValidation);
		}
	}

	/** 방향을 확정할 수 있는 OCO와 OTO에서 원주문과 완전히 같은 불필요한 정정을 차단합니다. */
	private void validateActuallyChanged(
			ConditionalOrderDetailResponse original,
			ConditionalOrderModificationPreviewRequest request) {
		if (original.type() == ConditionalOrderType.SINGLE
				|| original.type() != request.type()) return;
		boolean unchanged = sameDecimal(original.quantity(), request.quantity())
				&& original.orderType() == request.orderType()
				&& Objects.equals(original.expireDate(), request.expireDate())
				&& sameRequestedValues(original.first(), request.first())
				&& sameRequestedValues(original.second(), request.second());
		if (unchanged) {
			throw new OrderPreviewException(
					"원조건 주문과 정정 후 전체 구성이 같습니다. 변경할 값을 입력해 주세요.");
		}
	}

	/** 조회 조건과 사용자가 요청한 조건의 비교 가능한 가격값이 같은지 확인합니다. */
	private boolean sameRequestedValues(
			Condition original,
			ConditionalOrderModificationPreviewRequest.Condition requested) {
		if (original == null || requested == null) return original == null && requested == null;
		return sameDecimal(original.triggerPrice(), requested.triggerPrice())
				&& sameDecimal(original.orderPrice(), requested.orderPrice());
	}

	/** 현재가 응답이 원조건 주문의 종목·시장과 일치하는지 검사합니다. */
	private Market resolveMarket(
			ConditionalOrderDetailResponse order, StockPriceResponse stockPrice,
			boolean finalValidation) {
		if (stockPrice == null || stockPrice.symbol() == null
				|| !order.symbol().equalsIgnoreCase(stockPrice.symbol())
				|| stockPrice.price() == null || stockPrice.price().signum() <= 0) {
			throw validationException("조건 주문 정정에 사용할 현재가가 올바르지 않습니다.", finalValidation);
		}
		Market market = switch (stockPrice.currency()) {
			case "KRW" -> new Market("KR", "KRW");
			case "USD" -> new Market("US", "USD");
			case null, default -> throw validationException("지원하지 않는 거래 통화입니다.", finalValidation);
		};
		if (!order.market().name().equals(market.marketCountry())) {
			throw validationException("원조건 주문 시장과 현재가 통화가 일치하지 않습니다.", finalValidation);
		}
		return market;
	}

	/** 정정 후 유형별 조건, 시장 규칙, 계좌 여력과 위험 금액을 모두 검사합니다. */
	private ValidationResult validateRequestedOrder(
			long accountSeq, String symbol, ConditionalOrderType type, BigDecimal quantity,
			OrderType orderType, LocalDate expireDate, RequestedCondition first,
			RequestedCondition second, BigDecimal currentPrice, Market market,
			boolean finalValidation) {
		if (expireDate == null || expireDate.isBefore(LocalDate.now(clock))) {
			throw validationException("정정 후 조건 감시 만료일이 지났습니다.", finalValidation);
		}
		validateQuantity(quantity, type, orderType, first, market, finalValidation);
		validateConditionCount(type, first, second, finalValidation);
		validateCondition(first, orderType, market, "첫 번째", finalValidation);
		if (second != null) validateCondition(second, orderType, market, "두 번째", finalValidation);
		validateTypeRules(type, orderType, first, second, currentPrice, finalValidation);

		BigDecimal commissionRate = findCommissionRate(accountSeq, market.marketCountry(), finalValidation);
		BigDecimal firstAmount = calculationPrice(orderType, first.orderPrice(), currentPrice)
				.multiply(quantity);
		BigDecimal secondAmount = second == null ? BigDecimal.ZERO
				: calculationPrice(orderType, second.orderPrice(), currentPrice).multiply(quantity);
		boolean highValue = validateRiskAmounts(
				market, firstAmount, secondAmount, finalValidation);
		validateAccountCapacity(accountSeq, symbol, type, quantity, first,
				firstAmount, commissionRate, market.currency(), finalValidation);
		return new ValidationResult(highValue, firstAmount.max(secondAmount));
	}

	/** 유형에 맞는 정수 또는 허용된 미국 소수 수량인지 검사합니다. */
	private void validateQuantity(
			BigDecimal quantity, ConditionalOrderType type, OrderType orderType,
			RequestedCondition first, Market market, boolean finalValidation) {
		if (quantity == null || quantity.signum() <= 0 || quantity.toPlainString().length() > 30) {
			throw validationException("정정 수량은 0보다 크고 30자 이하여야 합니다.", finalValidation);
		}
		int scale = normalizedScale(quantity);
		if (scale == 0) return;
		boolean fractionalAllowed = type == ConditionalOrderType.SINGLE
				&& "US".equals(market.marketCountry()) && orderType == OrderType.MARKET
				&& first != null && first.side() == OrderSide.SELL;
		if (!fractionalAllowed) {
			throw validationException(
					"소수점 수량은 미국 SINGLE 시장가 매도 정정에만 사용할 수 있습니다.", finalValidation);
		}
		if (scale > 6) throw validationException("미국 주식 소수점 수량은 6자리까지 사용할 수 있습니다.", finalValidation);
	}

	/** SINGLE은 한 조건, OCO와 OTO는 두 조건을 갖는지 확인합니다. */
	private void validateConditionCount(
			ConditionalOrderType type, RequestedCondition first, RequestedCondition second,
			boolean finalValidation) {
		if (type == null || first == null
				|| (type == ConditionalOrderType.SINGLE && second != null)
				|| (type != ConditionalOrderType.SINGLE && second == null)) {
			throw validationException("정정 후 유형과 감시 조건 개수가 일치하지 않습니다.", finalValidation);
		}
	}

	/** 한 새 감시 조건의 방향과 시장별 가격 자릿수를 검사합니다. */
	private void validateCondition(
			RequestedCondition condition, OrderType orderType, Market market,
			String name, boolean finalValidation) {
		if (condition.side() == null) throw validationException(name + " 조건 방향이 필요합니다.", finalValidation);
		validatePrice(condition.triggerPrice(), name + " 감시가격", market, finalValidation);
		if (orderType == OrderType.MARKET) {
			if (condition.orderPrice() != null) throw validationException("시장가 조건에는 주문가격을 입력할 수 없습니다.", finalValidation);
		} else {
			validatePrice(condition.orderPrice(), name + " 주문가격", market, finalValidation);
		}
	}

	/** 새 유형의 주문 유형·방향과 OCO 가격 관계를 검사합니다. */
	private void validateTypeRules(
			ConditionalOrderType type, OrderType orderType, RequestedCondition first,
			RequestedCondition second, BigDecimal currentPrice, boolean finalValidation) {
		if (type == ConditionalOrderType.SINGLE) return;
		if (orderType != OrderType.LIMIT) {
			throw validationException(type + " 조건 주문은 지정가만 사용할 수 있습니다.", finalValidation);
		}
		if (type == ConditionalOrderType.OCO) {
			if (first.side() != OrderSide.SELL || second.side() != OrderSide.SELL) {
				throw validationException("OCO의 두 조건은 모두 매도여야 합니다.", finalValidation);
			}
			if (first.triggerPrice().compareTo(currentPrice) <= 0
					|| currentPrice.compareTo(second.triggerPrice()) <= 0) {
				throw validationException("OCO는 첫 감시가격 > 현재가 > 두 번째 감시가격 관계여야 합니다.", finalValidation);
			}
		} else if (first.side() != OrderSide.BUY || second.side() != OrderSide.SELL) {
			throw validationException("OTO는 첫 매수 조건과 두 번째 매도 조건으로 구성해야 합니다.", finalValidation);
		}
	}

	/** 가격이 양수이고 국내·미국 시장의 소수 자릿수 규칙을 지키는지 검사합니다. */
	private void validatePrice(
			BigDecimal price, String name, Market market, boolean finalValidation) {
		if (price == null || price.signum() <= 0 || price.toPlainString().length() > 30) {
			throw validationException(name + "은 0보다 크고 30자 이하여야 합니다.", finalValidation);
		}
		int scale = normalizedScale(price);
		if ("KR".equals(market.marketCountry()) && scale > 0) {
			throw validationException("국내 주식 " + name + "은 원 단위 정수여야 합니다.", finalValidation);
		}
		if ("US".equals(market.marketCountry())) {
			int maxScale = price.compareTo(BigDecimal.ONE) < 0 ? 4 : 2;
			if (scale > maxScale) throw validationException("미국 주식 " + name + "의 소수 자릿수가 너무 많습니다.", finalValidation);
		}
	}

	/** 지정가는 조건 주문가격을, 시장가는 최신 현재가를 계산 가격으로 사용합니다. */
	private BigDecimal calculationPrice(
			OrderType orderType, BigDecimal orderPrice, BigDecimal currentPrice) {
		return orderType == OrderType.LIMIT ? orderPrice : currentPrice;
	}

	/** 국내 조건별 30억원 상한과 1억원 사용자 확인 필요 여부를 계산합니다. */
	private boolean validateRiskAmounts(
			Market market, BigDecimal firstAmount, BigDecimal secondAmount,
			boolean finalValidation) {
		if (!"KRW".equals(market.currency())) return false;
		if (firstAmount.compareTo(MAX_KRW_ORDER_AMOUNT) > 0
				|| secondAmount.compareTo(MAX_KRW_ORDER_AMOUNT) > 0) {
			throw validationException("국내 조건 주문 정정 금액은 조건별 30억원을 초과할 수 없습니다.", finalValidation);
		}
		return firstAmount.compareTo(HIGH_VALUE_KRW_THRESHOLD) >= 0
				|| secondAmount.compareTo(HIGH_VALUE_KRW_THRESHOLD) >= 0;
	}

	/** 새 유형의 실제 자금 또는 매도 가능 수량이 충분한지 검사합니다. */
	private void validateAccountCapacity(
			long accountSeq, String symbol, ConditionalOrderType type, BigDecimal quantity,
			RequestedCondition first, BigDecimal firstAmount, BigDecimal commissionRate,
			String currency, boolean finalValidation) {
		boolean needsBuyingPower = type == ConditionalOrderType.OTO
				|| (type == ConditionalOrderType.SINGLE && first.side() == OrderSide.BUY);
		if (needsBuyingPower) {
			BuyingPowerResponse response = buyingPowerClient.getBuyingPower(accountSeq, currency);
			BigDecimal required = firstAmount.add(firstAmount.multiply(commissionRate));
			if (response == null || response.accountSeq() != accountSeq
					|| !currency.equals(response.currency()) || response.cashBuyingPower() == null
					|| response.cashBuyingPower().signum() < 0) {
				throw validationException("매수 가능 금액 응답이 요청 계좌와 일치하지 않습니다.", finalValidation);
			}
			if (response.cashBuyingPower().compareTo(required) < 0) {
				throw validationException("정정 후 매수에 필요한 금액이 부족합니다.", finalValidation);
			}
			return;
		}
		SellableQuantityResponse response = sellableQuantityClient.getSellableQuantity(accountSeq, symbol);
		if (response == null || response.accountSeq() != accountSeq || response.symbol() == null
				|| !symbol.equalsIgnoreCase(response.symbol()) || response.sellableQuantity() == null
				|| response.sellableQuantity().signum() < 0) {
			throw validationException("매도 가능 수량 응답이 요청 계좌와 일치하지 않습니다.", finalValidation);
		}
		if (response.sellableQuantity().compareTo(quantity) < 0) {
			throw validationException("정정 후 매도 가능 수량이 부족합니다.", finalValidation);
		}
	}

	/** 계좌 수수료 목록에서 원조건 주문 시장의 안전한 수수료율을 찾습니다. */
	private BigDecimal findCommissionRate(
			long accountSeq, String marketCountry, boolean finalValidation) {
		CommissionsResponse response = commissionsClient.getCommissions(accountSeq);
		List<CommissionItem> items = response == null ? null : response.commissions();
		if (response == null || response.accountSeq() != accountSeq || items == null) {
			throw validationException("매매 수수료 정보를 확인하지 못했습니다.", finalValidation);
		}
		CommissionItem item = items.stream()
				.filter(value -> value != null && marketCountry.equals(value.marketCountry()))
				.findFirst().orElseThrow(() -> validationException(
						"해당 시장의 매매 수수료를 찾지 못했습니다.", finalValidation));
		if (item.commissionRate() == null || item.commissionRate().signum() < 0
				|| item.commissionRate().compareTo(MAX_COMMISSION_RATE) > 0) {
			throw validationException("계산에 사용할 매매 수수료율이 올바르지 않습니다.", finalValidation);
		}
		return item.commissionRate();
	}

	/** 승인한 원주문의 모든 의미 있는 값과 최신 조회값이 같은지 검사합니다. */
	private void validateUnchangedOriginal(
			ConditionalOrderModificationPreviewResponse preview,
			ConditionalOrderDetailResponse current) {
		if (preview.originalType() != current.type() || preview.originalStatus() != current.status()
				|| !Objects.equals(preview.symbol(), current.symbol()) || preview.market() != current.market()
				|| !sameDecimal(preview.originalQuantity(), current.quantity())
				|| preview.originalOrderType() != current.orderType()
				|| !Objects.equals(preview.originalExpireDate(), current.expireDate())
				|| !sameCondition(preview.originalFirst(), current.first())
				|| !sameCondition(preview.originalSecond(), current.second())
				|| !sameInstant(preview.originalCreatedAt(), current.createdAt())) {
			throw new OrderExecutionConflictException(
					"승인 뒤 원조건 주문 내용이나 상태가 변경되었습니다. 새 정정 미리보기를 만들어 주세요.");
		}
	}

	/** 저장된 기존 조건 사본과 최신 감시 조건을 금융값 기준으로 비교합니다. */
	private boolean sameCondition(OriginalCondition saved, Condition current) {
		if (saved == null || current == null) return saved == null && current == null;
		return saved.type() == current.type() && saved.status() == current.status()
				&& sameDecimal(saved.triggerPrice(), current.triggerPrice())
				&& sameDecimal(saved.targetProfitRate(), current.targetProfitRate())
				&& sameDecimal(saved.orderPrice(), current.orderPrice())
				&& Objects.equals(saved.triggeredOrderId(), current.triggeredOrderId());
	}

	/** 소수 표기가 달라도 실제 금융값이 같은지 비교합니다. */
	private boolean sameDecimal(BigDecimal left, BigDecimal right) {
		return left == null ? right == null : right != null && left.compareTo(right) == 0;
	}

	/** 시간대 표기가 달라도 실제 같은 시각인지 비교합니다. */
	private boolean sameInstant(OffsetDateTime left, OffsetDateTime right) {
		return left == null ? right == null : right != null && left.isEqual(right);
	}

	/** 조회한 기존 감시 조건을 승인용 불변 사본으로 변환합니다. */
	private OriginalCondition snapshot(Condition condition) {
		return condition == null ? null : new OriginalCondition(
				condition.type(), condition.status(), condition.triggerPrice(),
				condition.targetProfitRate(), condition.orderPrice(), condition.triggeredOrderId());
	}

	/** 외부 요청의 새 감시 조건을 저장용 사본으로 변환합니다. */
	private RequestedCondition toRequested(ConditionalOrderModificationPreviewRequest.Condition value) {
		return value == null ? null : new RequestedCondition(
				value.side(), value.triggerPrice(), value.orderPrice());
	}

	/** 승인된 미리보기의 새 전체 구성을 실행 경계 요청으로 변환합니다. */
	private ConditionalOrderModificationSubmissionRequest toSubmissionRequest(
			ConditionalOrderModificationPreviewResponse preview,
			BrokerOrderRiskSnapshot riskSnapshot) {
		return new ConditionalOrderModificationSubmissionRequest(
				preview.requestedType(), preview.requestedQuantity(), preview.requestedOrderType(),
				preview.requestedExpireDate(), toSubmissionCondition(preview.requestedFirst()),
				toSubmissionCondition(preview.requestedSecond()),
				preview.requiresHighValueConfirmation(), riskSnapshot, preview.symbol());
	}

	/** 저장된 새 감시 조건을 증권사 경계의 조건 요청으로 변환합니다. */
	private ConditionalOrderModificationSubmissionRequest.Condition toSubmissionCondition(
			RequestedCondition value) {
		return value == null ? null : new ConditionalOrderModificationSubmissionRequest.Condition(
				value.side(), value.triggerPrice(), value.orderPrice());
	}

	/** 승인 상태·유효시간·새 만료일을 실행 직전에 확인합니다. */
	private void validateExecutablePreview(
			ConditionalOrderModificationPreviewResponse preview, OffsetDateTime now) {
		if (preview.status() != ConditionalOrderModificationPreviewStatus.APPROVED) {
			throw new OrderPreviewStateException("승인된 조건 주문 정정 미리보기만 실행할 수 있습니다.");
		}
		if (!preview.expiresAt().isAfter(now)) {
			throw new OrderPreviewExpiredException("조건 주문 정정 미리보기의 실행 시간이 지났습니다.");
		}
		if (preview.requestedExpireDate().isBefore(LocalDate.now(clock))) {
			throw new OrderExecutionValidationException("정정 후 조건 감시 만료일이 지났습니다.");
		}
	}

	/** 정정 응답이 원주문과 다른 안전한 새 조건 주문 식별값을 가졌는지 검사합니다. */
	private void validateModificationResponse(
			ConditionalOrderModificationResponse response, String originalId) {
		if (response == null || response.conditionalOrderId() == null
				|| response.conditionalOrderId().isBlank()
				|| response.conditionalOrderId().equals(originalId)
				|| response.conditionalOrderId().length() > 512
				|| response.conditionalOrderId().codePoints().anyMatch(codePoint ->
						Character.isWhitespace(codePoint) || codePoint < 32 || codePoint == 127)) {
			throw new OrderSubmissionException("조건 주문 정정 응답 형식이 올바르지 않습니다.", true);
		}
	}

	/** UUID 경로 식별값 형식을 검사합니다. */
	private void validateUuid(String value, String name) {
		if (value == null) throw new OrderPreviewException(name + " 식별값이 필요합니다.");
		try {
			UUID.fromString(value);
		} catch (IllegalArgumentException exception) {
			throw new OrderPreviewException(name + " 식별값 형식이 올바르지 않습니다.");
		}
	}

	/** 저장소에서 조건 주문 정정 미리보기를 찾거나 없음 오류를 냅니다. */
	private ConditionalOrderModificationPreviewResponse findPreview(String previewId) {
		return previewStore.findById(previewId)
				.orElseThrow(() -> new OrderPreviewNotFoundException(
						"조건 주문 정정 미리보기를 찾을 수 없습니다."));
	}

	/** 저장소에서 조건 주문 정정 실행을 찾거나 내부 저장 오류를 냅니다. */
	private ConditionalOrderModificationExecutionResponse findExecution(String executionId) {
		return executionStore.findById(executionId)
				.orElseThrow(() -> new OrderExecutionSubmissionException(
						"저장된 조건 주문 정정 실행 결과를 찾지 못했습니다."));
	}

	/** 미리보기와 최종 실행 단계에 맞는 검증 예외를 만듭니다. */
	private RuntimeException validationException(String message, boolean finalValidation) {
		return finalValidation ? new OrderExecutionValidationException(message)
				: new OrderPreviewException(message);
	}

	/** 값 끝의 불필요한 0을 제외한 실제 소수 자릿수를 반환합니다. */
	private int normalizedScale(BigDecimal value) {
		return Math.max(value.stripTrailingZeros().scale(), 0);
	}

	/** 시장 국가 코드와 거래 통화를 함께 전달합니다. */
	private record Market(String marketCountry, String currency) {
	}

	/** 새 전체 조건 검증에서 사용자 고액 확인 필요 여부를 전달합니다. */
	private record ValidationResult(
			boolean requiresHighValueConfirmation,
			BigDecimal maximumOrderAmount) {
	}
}
