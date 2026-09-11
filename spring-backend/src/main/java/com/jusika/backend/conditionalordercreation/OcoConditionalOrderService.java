package com.jusika.backend.conditionalordercreation;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.jusika.backend.commission.CommissionsResponse;
import com.jusika.backend.commission.CommissionsResponse.CommissionItem;
import com.jusika.backend.conditionalorder.ConditionalOrderCreationResponse;
import com.jusika.backend.conditionalorder.ConditionalOrderType;
import com.jusika.backend.conditionalorder.OcoConditionalOrderSubmissionRequest;
import com.jusika.backend.conditionalordercreation.OcoConditionalOrderPreviewRequest.Condition;
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
import com.jusika.backend.orderpreview.OrderPreviewStatus;
import com.jusika.backend.orderpreview.OrderSide;
import com.jusika.backend.orderpreview.OrderType;
import com.jusika.backend.sellablequantity.SellableQuantityResponse;
import com.jusika.backend.stock.StockPriceResponse;
import com.jusika.backend.toss.market.TossPriceClient;
import com.jusika.backend.toss.orderinfo.TossCommissionsClient;
import com.jusika.backend.toss.orderinfo.TossSellableQuantityClient;

/**
 * 두 매도 조건 중 하나가 체결되면 나머지를 취소하는 OCO 주문을 안전하게 검증하고 실행합니다.
 */
@Service
public class OcoConditionalOrderService {

	private static final BigDecimal HIGH_VALUE_KRW_THRESHOLD = new BigDecimal("100000000");
	private static final BigDecimal MAX_KRW_ORDER_AMOUNT = new BigDecimal("3000000000");
	private static final BigDecimal MAX_COMMISSION_RATE = BigDecimal.ONE;

	private final TossPriceClient priceClient;
	private final TossSellableQuantityClient sellableQuantityClient;
	private final TossCommissionsClient commissionsClient;
	private final OcoConditionalOrderPreviewStore previewStore;
	private final OcoConditionalOrderExecutionStore executionStore;
	private final OcoConditionalOrderGateway submissionGateway;
	private final OrderPreviewProperties properties;
	private final Clock clock;

	/**
	 * OCO 미리보기와 실행에 필요한 조회 기능·저장소·제출 경계·시계를 전달받습니다.
	 *
	 * @param priceClient 종목 현재가 조회 클라이언트
	 * @param sellableQuantityClient 종목별 매도 가능 수량 조회 클라이언트
	 * @param commissionsClient 시장별 수수료 조회 클라이언트
	 * @param previewStore OCO 미리보기 저장소
	 * @param executionStore 중복 실행을 막고 상태를 기록할 저장소
	 * @param submissionGateway 현재 설정된 모의 또는 실제 제출 경계
	 * @param properties 승인 유효시간 설정
	 * @param clock 생성·승인·실행 시각을 기록할 시스템 시계
	 */
	public OcoConditionalOrderService(
			TossPriceClient priceClient,
			TossSellableQuantityClient sellableQuantityClient,
			TossCommissionsClient commissionsClient,
			OcoConditionalOrderPreviewStore previewStore,
			OcoConditionalOrderExecutionStore executionStore,
			OcoConditionalOrderGateway submissionGateway,
			OrderPreviewProperties properties,
			Clock clock) {
		this.priceClient = priceClient;
		this.sellableQuantityClient = sellableQuantityClient;
		this.commissionsClient = commissionsClient;
		this.previewStore = previewStore;
		this.executionStore = executionStore;
		this.submissionGateway = submissionGateway;
		this.properties = properties;
		this.clock = clock;
	}

	/**
	 * 현재가가 두 감시가격 사이인지와 매도 가능 수량을 확인하고 변경 불가 미리보기를 저장합니다.
	 * 이 함수는 조건 주문 생성 API를 호출하지 않습니다.
	 *
	 * @param request 사용자가 확인하려는 OCO 조건 주문
	 * @return 현재가·수수료·매도 가능 수량을 검증한 미리보기
	 */
	public OcoConditionalOrderPreviewResponse createPreview(
			OcoConditionalOrderPreviewRequest request) {
		validateBasicRequest(request);
		String symbol = request.symbol().toUpperCase(Locale.ROOT);
		StockPriceResponse stockPrice = priceClient.getCurrentPrice(symbol);
		Market market = resolveMarket(symbol, stockPrice);
		validateQuantity(request.quantity());
		validateCondition(request.first(), "첫 번째", market);
		validateCondition(request.second(), "두 번째", market);
		validateTriggerRelationship(
				request.first().triggerPrice(), stockPrice.price(),
				request.second().triggerPrice(), false);
		validateSellableQuantity(request.accountSeq(), symbol, request.quantity(), false);
		BigDecimal commissionRate = findCommissionRate(
				request.accountSeq(), market.marketCountry(), false);
		LegCalculation first = calculateLeg(
				request.quantity(), request.first().orderPrice(), commissionRate, false);
		LegCalculation second = calculateLeg(
				request.quantity(), request.second().orderPrice(), commissionRate, false);
		boolean requiresHighValueConfirmation = requiresHighValueConfirmation(
				market, first.orderAmount(), second.orderAmount(), false);
		OffsetDateTime createdAt = OffsetDateTime.now(clock);

		return previewStore.save(new OcoConditionalOrderPreviewResponse(
				UUID.randomUUID().toString(), createdAt,
				createdAt.plus(properties.expiration()), request.accountSeq(), symbol,
				ConditionalOrderType.OCO, request.quantity(), request.orderType(),
				request.expireDate(), stockPrice.price(), market.currency(),
				market.marketCountry(), commissionRate,
				toPreviewCondition(request.first(), first),
				toPreviewCondition(request.second(), second), true,
				requiresHighValueConfirmation, OrderPreviewStatus.PENDING_APPROVAL, null));
	}

	/**
	 * 유효시간 안의 승인 대기 OCO 미리보기만 내용 변경 없이 한 번 승인합니다.
	 * 이 함수는 조건 주문 생성 API를 호출하지 않습니다.
	 *
	 * @param previewId 승인할 OCO 미리보기 식별값
	 * @return 승인 시각과 상태가 기록된 저장된 미리보기
	 */
	public OcoConditionalOrderPreviewResponse approvePreview(String previewId) {
		validateUuid(previewId, "OCO 미리보기");
		OffsetDateTime approvedAt = OffsetDateTime.now(clock);
		previewStore.expirePending(previewId, approvedAt);
		if (previewStore.approvePending(previewId, approvedAt)) {
			return findPreview(previewId);
		}
		OcoConditionalOrderPreviewResponse preview = findPreview(previewId);
		switch (preview.status()) {
			case EXPIRED -> throw new OrderPreviewExpiredException(
					"OCO 미리보기의 승인 시간이 지났습니다. 새 미리보기를 만들어 주세요.");
			case APPROVED -> throw new OrderPreviewStateException(
					"이미 승인한 OCO 미리보기입니다.");
			case CONSUMED -> throw new OrderPreviewStateException(
					"이미 실행에 사용한 OCO 미리보기입니다.");
			case PENDING_APPROVAL -> throw new OrderPreviewStateException(
					"OCO 미리보기 상태가 변경되어 승인하지 못했습니다.");
		}
		throw new IllegalStateException("처리할 수 없는 OCO 미리보기 상태입니다.");
	}

	/**
	 * 승인된 OCO의 현재가 관계와 계좌 여력을 다시 확인한 뒤 한 번만 현재 모드로 제출합니다.
	 * 기본 모드에서는 모의 게이트웨이만 호출하므로 실제 조건 주문은 생성되지 않습니다.
	 *
	 * @param previewId 실행할 OCO 미리보기 식별값
	 * @return 데이터베이스에 기록된 최종 OCO 실행 상태
	 */
	public OcoConditionalOrderExecutionResponse executeApprovedPreview(String previewId) {
		validateUuid(previewId, "OCO 미리보기");
		OcoConditionalOrderPreviewResponse preview = findPreview(previewId);
		OffsetDateTime startedAt = OffsetDateTime.now(clock);
		validateExecutablePreview(preview, startedAt);
		submissionGateway.requireSubmissionAvailable(preview.accountSeq());
		Revalidation revalidation = revalidateConditions(preview);
		if (revalidation.requiresHighValueConfirmation()
				&& !preview.requiresHighValueConfirmation()) {
			throw new OrderExecutionValidationException(
					"주문금액이 1억원 이상으로 변경되었습니다. 새 OCO 미리보기를 만들어 주세요.");
		}

		String executionId = UUID.randomUUID().toString();
		String clientOrderId = UUID.randomUUID().toString();
		OcoConditionalOrderExecutionResponse prepared =
				new OcoConditionalOrderExecutionResponse(
						executionId, preview.previewId(), clientOrderId, null,
						submissionGateway.mode(), OrderExecutionStatus.PREPARED, null,
						startedAt, startedAt, null, null);
		if (!executionStore.claim(prepared)) {
			throw new OrderExecutionConflictException(
					"이미 실행했거나 실행 중인 OCO 미리보기입니다.");
		}
		if (!previewStore.consumeApproved(previewId, startedAt)) {
			executionStore.markPreparationFailed(executionId, startedAt);
			throw new OrderExecutionConflictException(
					"OCO 미리보기의 승인 상태가 변경되었습니다.");
		}
		if (!executionStore.markSubmitting(executionId, startedAt)) {
			executionStore.markPreparationFailed(executionId, startedAt);
			throw new OrderExecutionSubmissionException(
					"OCO 실행 상태를 제출 중으로 변경하지 못했습니다.");
		}

		OcoConditionalOrderSubmissionRequest submission =
				new OcoConditionalOrderSubmissionRequest(
						clientOrderId, preview.symbol(), preview.quantity(), preview.orderType(),
						preview.expireDate(), toSubmissionCondition(preview.first()),
						toSubmissionCondition(preview.second()),
						preview.requiresHighValueConfirmation());
		return submitAndRecord(executionId, preview.accountSeq(), clientOrderId, submission);
	}

	/** 승인 뒤 달라질 수 있는 현재가·매도 가능 수량·수수료를 다시 확인합니다. */
	private Revalidation revalidateConditions(OcoConditionalOrderPreviewResponse preview) {
		try {
			StockPriceResponse stockPrice = priceClient.getCurrentPrice(preview.symbol());
			Market market = resolveMarket(preview.symbol(), stockPrice);
			validateUnchangedMarket(preview, market);
			validateTriggerRelationship(
					preview.first().triggerPrice(), stockPrice.price(),
					preview.second().triggerPrice(), true);
			validateSellableQuantity(
					preview.accountSeq(), preview.symbol(), preview.quantity(), true);
			BigDecimal commissionRate = findCommissionRate(
					preview.accountSeq(), market.marketCountry(), true);
			LegCalculation first = calculateLeg(
					preview.quantity(), preview.first().orderPrice(), commissionRate, true);
			LegCalculation second = calculateLeg(
					preview.quantity(), preview.second().orderPrice(), commissionRate, true);
			return new Revalidation(requiresHighValueConfirmation(
					market, first.orderAmount(), second.orderAmount(), true));
		} catch (OrderExecutionValidationException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			throw new OrderExecutionValidationException(
					"최종 OCO 조건 주문 정보를 확인하지 못했습니다. 조건 주문을 실행하지 않았습니다.");
		}
	}

	/**
	 * 실행 식별값으로 저장된 OCO 실행 기록을 읽기 전용으로 조회합니다.
	 *
	 * @param executionId 우리 서버가 만든 OCO 실행 식별값
	 * @return 저장된 OCO 실행 기록
	 */
	public OcoConditionalOrderExecutionResponse getExecution(String executionId) {
		validateUuid(executionId, "OCO 실행");
		return executionStore.findById(executionId)
				.orElseThrow(() -> new OrderExecutionNotFoundException(
						"OCO 실행 기록을 찾을 수 없습니다."));
	}

	/** 제출 결과를 접수·확정 거절·결과 불명 상태로 나눠 데이터베이스에 기록합니다. */
	private OcoConditionalOrderExecutionResponse submitAndRecord(
			String executionId,
			long accountSeq,
			String clientOrderId,
			OcoConditionalOrderSubmissionRequest request) {
		try {
			ConditionalOrderCreationResponse result = submissionGateway.submit(accountSeq, request);
			validateSubmissionResponse(result, clientOrderId);
			OffsetDateTime completedAt = OffsetDateTime.now(clock);
			if (!executionStore.markAccepted(
					executionId, result.conditionalOrderId(), completedAt)) {
				throw new OrderExecutionSubmissionException(
						"OCO 접수 결과를 데이터베이스에 기록하지 못했습니다.");
			}
			return findExecution(executionId);
		} catch (OrderSubmissionException exception) {
			OffsetDateTime failedAt = OffsetDateTime.now(clock);
			if (exception.isSubmissionStateUnknown()) {
				executionStore.markUnknown(executionId, failedAt);
				throw new OrderExecutionSubmissionException(
						"OCO 접수 여부를 확인할 수 없습니다. 자동으로 다시 생성하지 마세요.");
			}
			executionStore.markRejected(executionId, failedAt);
			throw new OrderExecutionSubmissionException("증권사가 OCO 생성을 거절했습니다.");
		} catch (OrderExecutionSubmissionException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			executionStore.markUnknown(executionId, OffsetDateTime.now(clock));
			throw new OrderExecutionSubmissionException(
					"OCO 접수 여부를 확인할 수 없습니다. 자동으로 다시 생성하지 마세요.");
		}
	}

	/** 외부 조회 전에 OCO 공통 필수 입력값과 만료일을 검사합니다. */
	private void validateBasicRequest(OcoConditionalOrderPreviewRequest request) {
		if (request == null) {
			throw new OrderPreviewException("OCO 미리보기 요청이 필요합니다.");
		}
		if (request.accountSeq() <= 0) {
			throw new OrderPreviewException("계좌 식별값은 1 이상이어야 합니다.");
		}
		if (request.symbol() == null
				|| request.symbol().length() > 32
				|| !request.symbol().matches("^[A-Za-z0-9.\\-]+$")) {
			throw new OrderPreviewException("종목 코드 형식이 올바르지 않습니다.");
		}
		if (request.quantity() == null || request.quantity().signum() <= 0
				|| request.quantity().toPlainString().length() > 30) {
			throw new OrderPreviewException("OCO 수량은 0보다 크고 30자 이하여야 합니다.");
		}
		if (request.orderType() != OrderType.LIMIT) {
			throw new OrderPreviewException("OCO 조건 주문은 지정가 주문만 사용할 수 있습니다.");
		}
		LocalDate today = LocalDate.now(clock);
		if (request.expireDate() == null || request.expireDate().isBefore(today)) {
			throw new OrderPreviewException("OCO 만료일은 오늘 또는 이후 날짜여야 합니다.");
		}
		if (request.first() == null || request.second() == null) {
			throw new OrderPreviewException("OCO의 첫 번째와 두 번째 조건이 모두 필요합니다.");
		}
	}

	/** OCO 지정가 주문 수량이 양의 정수인지 검사합니다. */
	private void validateQuantity(BigDecimal quantity) {
		if (normalizedScale(quantity) > 0) {
			throw new OrderPreviewException("OCO 지정가 주문 수량은 정수여야 합니다.");
		}
	}

	/** 한 OCO 조건의 방향·감시가격·주문가격을 검사합니다. */
	private void validateCondition(Condition condition, String name, Market market) {
		if (condition.side() != OrderSide.SELL) {
			throw new OrderPreviewException(name + " OCO 조건은 매도만 사용할 수 있습니다.");
		}
		validatePrice(condition.triggerPrice(), name + " 감시가격", market);
		validatePrice(condition.orderPrice(), name + " 주문가격", market);
	}

	/** 현재가가 첫 감시가격보다 낮고 두 번째 감시가격보다 높은지 엄격하게 검사합니다. */
	private void validateTriggerRelationship(
			BigDecimal firstTriggerPrice,
			BigDecimal currentPrice,
			BigDecimal secondTriggerPrice,
			boolean finalValidation) {
		if (firstTriggerPrice.compareTo(currentPrice) <= 0
				|| currentPrice.compareTo(secondTriggerPrice) <= 0) {
			throw validationException(
					"OCO는 첫 감시가격 > 현재가 > 두 번째 감시가격 관계여야 합니다.",
					finalValidation);
		}
	}

	/** 현재가의 종목·가격·통화를 검사하고 시장 정보로 변환합니다. */
	private Market resolveMarket(String symbol, StockPriceResponse stockPrice) {
		if (stockPrice == null || stockPrice.symbol() == null
				|| !symbol.equalsIgnoreCase(stockPrice.symbol())
				|| stockPrice.price() == null || stockPrice.price().signum() <= 0) {
			throw new OrderPreviewException("OCO 계산에 사용할 현재가가 올바르지 않습니다.");
		}
		return switch (stockPrice.currency()) {
			case "KRW" -> new Market("KR", "KRW");
			case "USD" -> new Market("US", "USD");
			case null, default -> throw new OrderPreviewException("지원하지 않는 거래 통화입니다.");
		};
	}

	/** 가격이 양수이고 국내·미국 시장의 소수 자릿수 규칙을 지키는지 검사합니다. */
	private void validatePrice(BigDecimal price, String name, Market market) {
		if (price == null || price.signum() <= 0 || price.toPlainString().length() > 30) {
			throw new OrderPreviewException(name + "은 0보다 크고 30자 이하여야 합니다.");
		}
		int scale = normalizedScale(price);
		if ("KR".equals(market.marketCountry()) && scale > 0) {
			throw new OrderPreviewException("국내 주식 " + name + "은 원 단위 정수여야 합니다.");
		}
		if ("US".equals(market.marketCountry())) {
			int maxScale = price.compareTo(BigDecimal.ONE) < 0 ? 4 : 2;
			if (scale > maxScale) {
				throw new OrderPreviewException("미국 주식 " + name + "의 소수 자릿수가 너무 많습니다.");
			}
		}
	}

	/** 두 조건 중 하나만 발동하므로 공통 수량을 한 번만 매도 가능 수량과 비교합니다. */
	private void validateSellableQuantity(
			long accountSeq,
			String symbol,
			BigDecimal quantity,
			boolean finalValidation) {
		SellableQuantityResponse response = sellableQuantityClient.getSellableQuantity(
				accountSeq, symbol);
		if (response == null || response.accountSeq() != accountSeq
				|| response.symbol() == null || !symbol.equalsIgnoreCase(response.symbol())
				|| response.sellableQuantity() == null || response.sellableQuantity().signum() < 0) {
			throw validationException(
					"매도 가능 수량 응답이 요청 계좌와 일치하지 않습니다.", finalValidation);
		}
		if (response.sellableQuantity().compareTo(quantity) < 0) {
			throw validationException("매도 가능 수량이 부족합니다.", finalValidation);
		}
	}

	/** 계좌 수수료 목록에서 OCO 종목 시장의 안전한 수수료율을 찾습니다. */
	private BigDecimal findCommissionRate(
			long accountSeq,
			String marketCountry,
			boolean finalValidation) {
		CommissionsResponse response = commissionsClient.getCommissions(accountSeq);
		List<CommissionItem> items = response == null ? null : response.commissions();
		if (response == null || response.accountSeq() != accountSeq || items == null) {
			throw validationException("매매 수수료 정보를 확인하지 못했습니다.", finalValidation);
		}
		CommissionItem item = items.stream()
				.filter(value -> value != null && marketCountry.equals(value.marketCountry()))
				.findFirst()
				.orElseThrow(() -> validationException(
						"해당 시장의 매매 수수료를 찾지 못했습니다.", finalValidation));
		if (item.commissionRate() == null || item.commissionRate().signum() < 0
				|| item.commissionRate().compareTo(MAX_COMMISSION_RATE) > 0) {
			throw validationException("계산에 사용할 매매 수수료율이 올바르지 않습니다.", finalValidation);
		}
		return item.commissionRate();
	}

	/** 한 조건이 발동했을 때의 주문금액·수수료·예상 수령액을 계산합니다. */
	private LegCalculation calculateLeg(
			BigDecimal quantity,
			BigDecimal orderPrice,
			BigDecimal commissionRate,
			boolean finalValidation) {
		BigDecimal orderAmount = orderPrice.multiply(quantity);
		if (orderAmount.signum() <= 0) {
			throw validationException("OCO 예상 주문금액이 올바르지 않습니다.", finalValidation);
		}
		BigDecimal commission = orderAmount.multiply(commissionRate);
		return new LegCalculation(orderAmount, commission, orderAmount.subtract(commission));
	}

	/** 국내 OCO의 어느 조건도 프로젝트 주문금액 상한을 넘지 않는지 검사합니다. */
	private boolean requiresHighValueConfirmation(
			Market market,
			BigDecimal firstOrderAmount,
			BigDecimal secondOrderAmount,
			boolean finalValidation) {
		if (!"KRW".equals(market.currency())) {
			return false;
		}
		if (firstOrderAmount.compareTo(MAX_KRW_ORDER_AMOUNT) > 0
				|| secondOrderAmount.compareTo(MAX_KRW_ORDER_AMOUNT) > 0) {
			throw validationException(
					"국내 OCO 주문금액은 안전 정책상 조건별 30억원을 초과할 수 없습니다.",
					finalValidation);
		}
		return firstOrderAmount.compareTo(HIGH_VALUE_KRW_THRESHOLD) >= 0
				|| secondOrderAmount.compareTo(HIGH_VALUE_KRW_THRESHOLD) >= 0;
	}

	/** 입력 조건과 계산 결과를 미리보기의 한 조건으로 합칩니다. */
	private OcoConditionalOrderPreviewResponse.Condition toPreviewCondition(
			Condition condition,
			LegCalculation calculation) {
		return new OcoConditionalOrderPreviewResponse.Condition(
				condition.side(), condition.triggerPrice(), condition.orderPrice(),
				calculation.orderAmount(), calculation.commission(), calculation.proceeds());
	}

	/** 저장된 미리보기 조건을 증권사 제출용 조건으로 변환합니다. */
	private OcoConditionalOrderSubmissionRequest.Condition toSubmissionCondition(
			OcoConditionalOrderPreviewResponse.Condition condition) {
		return new OcoConditionalOrderSubmissionRequest.Condition(
				condition.side(), condition.triggerPrice(), condition.orderPrice());
	}

	/** 실행 시점에도 미리보기와 같은 시장·통화인지 확인합니다. */
	private void validateUnchangedMarket(
			OcoConditionalOrderPreviewResponse preview,
			Market market) {
		if (!preview.marketCountry().equals(market.marketCountry())
				|| !preview.currency().equals(market.currency())) {
			throw new OrderExecutionValidationException(
					"승인 뒤 종목의 시장 또는 통화가 변경되었습니다.");
		}
	}

	/** 승인 상태·유효시간·OCO 감시 만료일이 실행 시점에도 유효한지 확인합니다. */
	private void validateExecutablePreview(
			OcoConditionalOrderPreviewResponse preview,
			OffsetDateTime now) {
		if (preview.status() != OrderPreviewStatus.APPROVED) {
			throw new OrderPreviewStateException("승인된 OCO 미리보기만 실행할 수 있습니다.");
		}
		if (!preview.expiresAt().isAfter(now)) {
			throw new OrderPreviewExpiredException("OCO 미리보기의 실행 시간이 지났습니다.");
		}
		if (preview.expireDate().isBefore(LocalDate.now(clock))) {
			throw new OrderExecutionValidationException("OCO 감시 만료일이 지났습니다.");
		}
	}

	/** 제출 결과의 조건 주문 식별값과 멱등성 식별값이 올바른지 확인합니다. */
	private void validateSubmissionResponse(
			ConditionalOrderCreationResponse response,
			String clientOrderId) {
		if (response == null || response.conditionalOrderId() == null
				|| response.conditionalOrderId().isBlank()
				|| !clientOrderId.equals(response.clientOrderId())) {
			throw new OrderSubmissionException("OCO 제출 응답 형식이 올바르지 않습니다.", true);
		}
	}

	/** 미리보기 또는 실행 식별값이 표준 UUID 문자열인지 검사합니다. */
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

	/** 저장된 OCO 미리보기를 찾거나 찾을 수 없음 오류를 발생시킵니다. */
	private OcoConditionalOrderPreviewResponse findPreview(String previewId) {
		return previewStore.findById(previewId)
				.orElseThrow(() -> new OrderPreviewNotFoundException(
						"OCO 미리보기를 찾을 수 없습니다."));
	}

	/** 저장된 OCO 실행을 찾거나 내부 저장 오류를 발생시킵니다. */
	private OcoConditionalOrderExecutionResponse findExecution(String executionId) {
		return executionStore.findById(executionId)
				.orElseThrow(() -> new OrderExecutionSubmissionException(
						"OCO 실행 결과를 데이터베이스에서 찾지 못했습니다."));
	}

	/** 미리보기와 최종 실행 단계에 맞는 검증 예외를 만듭니다. */
	private RuntimeException validationException(String message, boolean finalValidation) {
		return finalValidation
				? new OrderExecutionValidationException(message)
				: new OrderPreviewException(message);
	}

	/** 값 끝의 불필요한 0을 제외한 실제 소수 자릿수를 반환합니다. */
	private int normalizedScale(BigDecimal value) {
		return Math.max(value.stripTrailingZeros().scale(), 0);
	}

	/** 시장 국가 코드와 거래 통화를 함께 전달합니다. */
	private record Market(String marketCountry, String currency) {
	}

	/** 한 OCO 조건의 예상 주문금액·수수료·수령액을 함께 전달합니다. */
	private record LegCalculation(
			BigDecimal orderAmount,
			BigDecimal commission,
			BigDecimal proceeds) {
	}

	/** 최종 재검증에서 사용자가 다시 확인해야 하는 값만 전달합니다. */
	private record Revalidation(boolean requiresHighValueConfirmation) {
	}
}
