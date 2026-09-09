package com.jusika.backend.conditionalordercreation;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.jusika.backend.buyingpower.BuyingPowerResponse;
import com.jusika.backend.commission.CommissionsResponse;
import com.jusika.backend.commission.CommissionsResponse.CommissionItem;
import com.jusika.backend.conditionalorder.ConditionalOrderCreationResponse;
import com.jusika.backend.conditionalorder.ConditionalOrderType;
import com.jusika.backend.conditionalorder.SingleConditionalOrderSubmissionRequest;
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
import com.jusika.backend.toss.orderinfo.TossBuyingPowerClient;
import com.jusika.backend.toss.orderinfo.TossCommissionsClient;
import com.jusika.backend.toss.orderinfo.TossSellableQuantityClient;

/**
 * 단일 조건 주문 내용을 고정하고 승인·최종 재검증 뒤 중복 없는 모의 실행을 담당합니다.
 */
@Service
public class SingleConditionalOrderService {

	private static final BigDecimal HIGH_VALUE_KRW_THRESHOLD = new BigDecimal("100000000");
	private static final BigDecimal MAX_KRW_ORDER_AMOUNT = new BigDecimal("3000000000");
	private static final BigDecimal MAX_COMMISSION_RATE = BigDecimal.ONE;

	private final TossPriceClient priceClient;
	private final TossBuyingPowerClient buyingPowerClient;
	private final TossSellableQuantityClient sellableQuantityClient;
	private final TossCommissionsClient commissionsClient;
	private final SingleConditionalOrderPreviewStore previewStore;
	private final SingleConditionalOrderExecutionStore executionStore;
	private final SingleConditionalOrderGateway submissionGateway;
	private final OrderPreviewProperties properties;
	private final Clock clock;

	/**
	 * 미리보기·최종 검증에 필요한 조회 기능과 저장소·실행 경계·시계를 전달받습니다.
	 *
	 * @param priceClient 종목 현재가 조회 클라이언트
	 * @param buyingPowerClient 통화별 매수 가능 금액 조회 클라이언트
	 * @param sellableQuantityClient 종목별 매도 가능 수량 조회 클라이언트
	 * @param commissionsClient 시장별 수수료 조회 클라이언트
	 * @param previewStore 단일 조건 주문 미리보기 저장소
	 * @param executionStore 중복 실행을 막고 상태를 기록할 저장소
	 * @param submissionGateway 현재 설정된 모의 또는 실제 제출 경계
	 * @param properties 승인 유효시간 설정
	 * @param clock 생성·승인·실행 시각을 기록할 시스템 시계
	 */
	public SingleConditionalOrderService(
			TossPriceClient priceClient,
			TossBuyingPowerClient buyingPowerClient,
			TossSellableQuantityClient sellableQuantityClient,
			TossCommissionsClient commissionsClient,
			SingleConditionalOrderPreviewStore previewStore,
			SingleConditionalOrderExecutionStore executionStore,
			SingleConditionalOrderGateway submissionGateway,
			OrderPreviewProperties properties,
			Clock clock) {
		this.priceClient = priceClient;
		this.buyingPowerClient = buyingPowerClient;
		this.sellableQuantityClient = sellableQuantityClient;
		this.commissionsClient = commissionsClient;
		this.previewStore = previewStore;
		this.executionStore = executionStore;
		this.submissionGateway = submissionGateway;
		this.properties = properties;
		this.clock = clock;
	}

	/**
	 * 단일 조건 주문 형식과 현재 계좌 여력을 검사하고 승인할 변경 불가 사본을 저장합니다.
	 * 이 함수는 조건 주문 생성 API를 호출하지 않습니다.
	 *
	 * @param request 사용자가 확인하려는 단일 조건 주문
	 * @return 현재가·수수료·계좌 여력 검증을 마친 미리보기
	 */
	public SingleConditionalOrderPreviewResponse createPreview(
			SingleConditionalOrderPreviewRequest request) {
		validateBasicRequest(request);
		String symbol = request.symbol().toUpperCase(Locale.ROOT);
		StockPriceResponse stockPrice = priceClient.getCurrentPrice(symbol);
		Market market = resolveMarket(symbol, stockPrice);
		validateQuantity(request.quantity(), request.side(), request.orderType(), market);
		validatePrice(request.triggerPrice(), "감시가격", market);
		validateOrderPrice(request.orderPrice(), request.orderType(), market);

		Calculation calculation = calculateAndValidateAccount(
				request.accountSeq(), symbol, request.side(), request.orderType(),
				request.quantity(), request.orderPrice(), stockPrice.price(), market, false);
		OffsetDateTime createdAt = OffsetDateTime.now(clock);

		return previewStore.save(new SingleConditionalOrderPreviewResponse(
				UUID.randomUUID().toString(), createdAt,
				createdAt.plus(properties.expiration()), request.accountSeq(), symbol,
				ConditionalOrderType.SINGLE, request.side(), request.orderType(),
				request.quantity(), request.triggerPrice(), request.orderPrice(),
				request.expireDate(), stockPrice.price(), calculation.calculationPrice(),
				market.currency(), market.marketCountry(), calculation.commissionRate(),
				calculation.orderAmount(), calculation.commission(),
				calculation.amountAfterCommission(), request.side() == OrderSide.SELL,
				calculation.requiresHighValueConfirmation(),
				OrderPreviewStatus.PENDING_APPROVAL, null));
	}

	/**
	 * 유효시간 안의 승인 대기 미리보기만 내용 변경 없이 한 번 승인합니다.
	 * 이 함수는 조건 주문 생성 API를 호출하지 않습니다.
	 *
	 * @param previewId 승인할 단일 조건 주문 미리보기 식별값
	 * @return 승인 시각과 상태가 기록된 저장된 미리보기
	 */
	public SingleConditionalOrderPreviewResponse approvePreview(String previewId) {
		validateUuid(previewId, "조건 주문 미리보기");
		OffsetDateTime approvedAt = OffsetDateTime.now(clock);
		previewStore.expirePending(previewId, approvedAt);
		if (previewStore.approvePending(previewId, approvedAt)) {
			return findPreview(previewId);
		}
		SingleConditionalOrderPreviewResponse preview = findPreview(previewId);
		switch (preview.status()) {
			case EXPIRED -> throw new OrderPreviewExpiredException(
					"조건 주문 미리보기의 승인 시간이 지났습니다. 새 미리보기를 만들어 주세요.");
			case APPROVED -> throw new OrderPreviewStateException(
					"이미 승인한 조건 주문 미리보기입니다.");
			case CONSUMED -> throw new OrderPreviewStateException(
					"이미 실행에 사용한 조건 주문 미리보기입니다.");
			case PENDING_APPROVAL -> throw new OrderPreviewStateException(
					"조건 주문 미리보기 상태가 변경되어 승인하지 못했습니다.");
		}
		throw new IllegalStateException("처리할 수 없는 조건 주문 미리보기 상태입니다.");
	}

	/**
	 * 승인된 미리보기의 현재가와 계좌 여력을 다시 검사한 뒤 한 번만 현재 모드로 제출합니다.
	 * 기본 모드에서는 모의 게이트웨이만 호출하므로 실제 조건 주문은 생성되지 않습니다.
	 *
	 * @param previewId 실행할 단일 조건 주문 미리보기 식별값
	 * @return 데이터베이스에 기록된 최종 조건 주문 실행 상태
	 */
	public SingleConditionalOrderExecutionResponse executeApprovedPreview(String previewId) {
		validateUuid(previewId, "조건 주문 미리보기");
		SingleConditionalOrderPreviewResponse preview = findPreview(previewId);
		OffsetDateTime startedAt = OffsetDateTime.now(clock);
		validateExecutablePreview(preview, startedAt);
		submissionGateway.requireSubmissionAvailable();
		Calculation calculation = revalidateAccountConditions(preview);
		if (calculation.requiresHighValueConfirmation()
				&& !preview.requiresHighValueConfirmation()) {
			throw new OrderExecutionValidationException(
					"시장가 기준 금액이 1억원 이상으로 변경되었습니다. 새 미리보기를 만들어 주세요.");
		}

		String executionId = UUID.randomUUID().toString();
		String clientOrderId = UUID.randomUUID().toString();
		SingleConditionalOrderExecutionResponse prepared =
				new SingleConditionalOrderExecutionResponse(
						executionId, preview.previewId(), clientOrderId, null,
						submissionGateway.mode(), OrderExecutionStatus.PREPARED, null,
						startedAt, startedAt, null, null);
		if (!executionStore.claim(prepared)) {
			throw new OrderExecutionConflictException(
					"이미 실행했거나 실행 중인 조건 주문 미리보기입니다.");
		}
		if (!previewStore.consumeApproved(previewId, startedAt)) {
			executionStore.markPreparationFailed(executionId, startedAt);
			throw new OrderExecutionConflictException(
					"조건 주문 미리보기의 승인 상태가 변경되었습니다.");
		}
		if (!executionStore.markSubmitting(executionId, startedAt)) {
			executionStore.markPreparationFailed(executionId, startedAt);
			throw new OrderExecutionSubmissionException(
					"조건 주문 실행 상태를 제출 중으로 변경하지 못했습니다.");
		}

		SingleConditionalOrderSubmissionRequest submission =
				new SingleConditionalOrderSubmissionRequest(
						clientOrderId, preview.symbol(), preview.quantity(), preview.orderType(),
						preview.expireDate(), preview.side(), preview.triggerPrice(),
						preview.orderPrice(), preview.requiresHighValueConfirmation());
		return submitAndRecord(executionId, preview.accountSeq(), clientOrderId, submission);
	}

	/**
	 * 승인 뒤 변할 수 있는 현재가·수수료·매수 가능 금액 또는 매도 가능 수량을 다시 확인합니다.
	 *
	 * @param preview 사용자가 승인한 저장된 단일 조건 주문 미리보기
	 * @return 실행 시점의 예상 주문금액과 수수료 계산 결과
	 */
	private Calculation revalidateAccountConditions(
			SingleConditionalOrderPreviewResponse preview) {
		try {
			StockPriceResponse stockPrice = priceClient.getCurrentPrice(preview.symbol());
			Market market = resolveMarket(preview.symbol(), stockPrice);
			validateUnchangedMarket(preview, market);
			return calculateAndValidateAccount(
					preview.accountSeq(), preview.symbol(), preview.side(), preview.orderType(),
					preview.quantity(), preview.orderPrice(), stockPrice.price(), market, true);
		} catch (OrderExecutionValidationException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			throw new OrderExecutionValidationException(
					"최종 조건 주문 정보를 확인하지 못했습니다. 조건 주문을 실행하지 않았습니다.");
		}
	}

	/**
	 * 실행 식별값으로 저장된 단일 조건 주문 실행 기록을 읽기 전용으로 조회합니다.
	 *
	 * @param executionId 우리 서버가 만든 조건 주문 실행 식별값
	 * @return 저장된 조건 주문 실행 기록
	 */
	public SingleConditionalOrderExecutionResponse getExecution(String executionId) {
		validateUuid(executionId, "조건 주문 실행");
		return executionStore.findById(executionId)
				.orElseThrow(() -> new OrderExecutionNotFoundException(
						"조건 주문 실행 기록을 찾을 수 없습니다."));
	}

	/**
	 * 제출 결과를 접수·확정 거절·결과 불명 상태로 나눠 데이터베이스에 기록합니다.
	 *
	 * @param executionId 상태를 변경할 실행 식별값
	 * @param accountSeq 조건 주문에 사용할 계좌 식별값
	 * @param clientOrderId 요청에 사용한 멱등성 식별값
	 * @param request 최종 재검증을 마친 단일 조건 주문
	 * @return 접수 상태로 저장된 실행 응답
	 */
	private SingleConditionalOrderExecutionResponse submitAndRecord(
			String executionId,
			long accountSeq,
			String clientOrderId,
			SingleConditionalOrderSubmissionRequest request) {
		try {
			ConditionalOrderCreationResponse result = submissionGateway.submit(accountSeq, request);
			validateSubmissionResponse(result, clientOrderId);
			OffsetDateTime completedAt = OffsetDateTime.now(clock);
			if (!executionStore.markAccepted(
					executionId, result.conditionalOrderId(), completedAt)) {
				throw new OrderExecutionSubmissionException(
						"조건 주문 접수 결과를 데이터베이스에 기록하지 못했습니다.");
			}
			return findExecution(executionId);
		} catch (OrderSubmissionException exception) {
			OffsetDateTime failedAt = OffsetDateTime.now(clock);
			if (exception.isSubmissionStateUnknown()) {
				executionStore.markUnknown(executionId, failedAt);
				throw new OrderExecutionSubmissionException(
						"조건 주문 접수 여부를 확인할 수 없습니다. 자동으로 다시 생성하지 마세요.");
			}
			executionStore.markRejected(executionId, failedAt);
			throw new OrderExecutionSubmissionException("증권사가 조건 주문 생성을 거절했습니다.");
		} catch (OrderExecutionSubmissionException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			executionStore.markUnknown(executionId, OffsetDateTime.now(clock));
			throw new OrderExecutionSubmissionException(
					"조건 주문 접수 여부를 확인할 수 없습니다. 자동으로 다시 생성하지 마세요.");
		}
	}

	/** 외부 조회 전에 필수 입력값과 만료일을 검사합니다. */
	private void validateBasicRequest(SingleConditionalOrderPreviewRequest request) {
		if (request == null) {
			throw new OrderPreviewException("단일 조건 주문 미리보기 요청이 필요합니다.");
		}
		if (request.accountSeq() <= 0) {
			throw new OrderPreviewException("계좌 식별값은 1 이상이어야 합니다.");
		}
		if (request.symbol() == null
				|| !request.symbol().matches("^[A-Za-z0-9.\\-]+$")) {
			throw new OrderPreviewException("종목 코드 형식이 올바르지 않습니다.");
		}
		if (request.side() == null) {
			throw new OrderPreviewException("조건 주문 방향은 BUY 또는 SELL이어야 합니다.");
		}
		if (request.orderType() == null) {
			throw new OrderPreviewException("조건 주문 유형은 LIMIT 또는 MARKET이어야 합니다.");
		}
		if (request.quantity() == null || request.quantity().signum() <= 0
				|| request.quantity().toPlainString().length() > 30) {
			throw new OrderPreviewException("조건 주문 수량은 0보다 크고 30자 이하여야 합니다.");
		}
		LocalDate today = LocalDate.now(clock);
		if (request.expireDate() == null || request.expireDate().isBefore(today)) {
			throw new OrderPreviewException("조건 주문 만료일은 오늘 또는 이후 날짜여야 합니다.");
		}
	}

	/** 현재가의 종목·가격·통화를 검사하고 시장 정보로 변환합니다. */
	private Market resolveMarket(String symbol, StockPriceResponse stockPrice) {
		if (stockPrice == null || stockPrice.symbol() == null
				|| !symbol.equalsIgnoreCase(stockPrice.symbol())
				|| stockPrice.price() == null || stockPrice.price().signum() <= 0) {
			throw new OrderPreviewException("조건 주문 계산에 사용할 현재가가 올바르지 않습니다.");
		}
		return switch (stockPrice.currency()) {
			case "KRW" -> new Market("KR", "KRW");
			case "USD" -> new Market("US", "USD");
			case null, default -> throw new OrderPreviewException("지원하지 않는 거래 통화입니다.");
		};
	}

	/** 시장·방향·주문 유형에 맞는 정수 또는 소수 수량인지 검사합니다. */
	private void validateQuantity(
			BigDecimal quantity,
			OrderSide side,
			OrderType orderType,
			Market market) {
		int scale = normalizedScale(quantity);
		if (scale == 0) {
			return;
		}
		boolean fractionalAllowed = "US".equals(market.marketCountry())
				&& side == OrderSide.SELL && orderType == OrderType.MARKET;
		if (!fractionalAllowed) {
			throw new OrderPreviewException(
					"소수점 수량은 미국 주식 시장가 매도 조건 주문에만 사용할 수 있습니다.");
		}
		if (scale > 6) {
			throw new OrderPreviewException(
					"미국 주식 소수점 수량은 소수점 6자리까지 사용할 수 있습니다.");
		}
	}

	/** 감시가격이 양수이고 시장별 가격 소수 자릿수를 지키는지 검사합니다. */
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
				throw new OrderPreviewException(
						"미국 주식 " + name + "의 소수 자릿수가 너무 많습니다.");
			}
		}
	}

	/** 지정가는 주문가격이 필요하고 시장가는 주문가격을 받지 않는지 검사합니다. */
	private void validateOrderPrice(BigDecimal price, OrderType orderType, Market market) {
		if (orderType == OrderType.MARKET) {
			if (price != null) {
				throw new OrderPreviewException("시장가 조건 주문에는 주문가격을 입력할 수 없습니다.");
			}
			return;
		}
		if (price == null) {
			throw new OrderPreviewException("지정가 조건 주문에는 주문가격이 필요합니다.");
		}
		validatePrice(price, "주문가격", market);
	}

	/** 현재 가격·수수료와 매수 가능 금액 또는 매도 가능 수량을 검증하고 예상값을 계산합니다. */
	private Calculation calculateAndValidateAccount(
			long accountSeq,
			String symbol,
			OrderSide side,
			OrderType orderType,
			BigDecimal quantity,
			BigDecimal orderPrice,
			BigDecimal currentPrice,
			Market market,
			boolean finalValidation) {
		BigDecimal calculationPrice = orderType == OrderType.LIMIT ? orderPrice : currentPrice;
		BigDecimal orderAmount = calculationPrice.multiply(quantity);
		if ("KRW".equals(market.currency())
				&& orderAmount.compareTo(MAX_KRW_ORDER_AMOUNT) > 0) {
			throw validationException(
					"국내 조건 주문금액은 안전 정책상 30억원을 초과할 수 없습니다.",
					finalValidation);
		}
		BigDecimal commissionRate = findCommissionRate(
				accountSeq, market.marketCountry(), finalValidation);
		BigDecimal commission = orderAmount.multiply(commissionRate);
		BigDecimal amountAfterCommission = side == OrderSide.BUY
				? orderAmount.add(commission)
				: orderAmount.subtract(commission);

		if (side == OrderSide.BUY) {
			validateBuyingPower(
					accountSeq, market.currency(), amountAfterCommission, finalValidation);
		} else {
			validateSellableQuantity(accountSeq, symbol, quantity, finalValidation);
		}
		return new Calculation(
				calculationPrice, commissionRate, orderAmount, commission,
				amountAfterCommission,
				"KRW".equals(market.currency())
						&& orderAmount.compareTo(HIGH_VALUE_KRW_THRESHOLD) >= 0);
	}

	/** 매수 가능 금액 응답의 계좌·통화·금액을 확인하고 예상 필요 금액과 비교합니다. */
	private void validateBuyingPower(
			long accountSeq,
			String currency,
			BigDecimal requiredAmount,
			boolean finalValidation) {
		BuyingPowerResponse response = buyingPowerClient.getBuyingPower(accountSeq, currency);
		if (response == null || response.accountSeq() != accountSeq
				|| !currency.equals(response.currency()) || response.cashBuyingPower() == null
				|| response.cashBuyingPower().signum() < 0) {
			throw validationException("매수 가능 금액 응답이 요청 계좌와 일치하지 않습니다.", finalValidation);
		}
		if (response.cashBuyingPower().compareTo(requiredAmount) < 0) {
			throw validationException("매수 가능 금액이 부족합니다.", finalValidation);
		}
	}

	/** 매도 가능 수량 응답의 계좌·종목·수량을 확인하고 요청 수량과 비교합니다. */
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
			throw validationException("매도 가능 수량 응답이 요청 계좌와 일치하지 않습니다.", finalValidation);
		}
		if (response.sellableQuantity().compareTo(quantity) < 0) {
			throw validationException("매도 가능 수량이 부족합니다.", finalValidation);
		}
	}

	/** 계좌 수수료 목록에서 해당 시장의 안전한 수수료율을 찾습니다. */
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

	/** 실행 시점에도 미리보기와 같은 시장·통화인지 확인합니다. */
	private void validateUnchangedMarket(
			SingleConditionalOrderPreviewResponse preview,
			Market market) {
		if (!preview.marketCountry().equals(market.marketCountry())
				|| !preview.currency().equals(market.currency())) {
			throw new OrderExecutionValidationException(
					"승인 뒤 종목의 시장 또는 통화가 변경되었습니다.");
		}
	}

	/** 승인 상태·유효시간·조건 만료일이 실행 시점에도 유효한지 확인합니다. */
	private void validateExecutablePreview(
			SingleConditionalOrderPreviewResponse preview,
			OffsetDateTime now) {
		if (preview.status() != OrderPreviewStatus.APPROVED) {
			throw new OrderPreviewStateException(
					"승인된 조건 주문 미리보기만 실행할 수 있습니다.");
		}
		if (!preview.expiresAt().isAfter(now)) {
			throw new OrderPreviewExpiredException("조건 주문 미리보기의 실행 시간이 지났습니다.");
		}
		if (preview.expireDate().isBefore(LocalDate.now(clock))) {
			throw new OrderExecutionValidationException("조건 주문 만료일이 지났습니다.");
		}
	}

	/** 제출 결과의 조건 주문 식별값과 멱등성 식별값이 올바른지 확인합니다. */
	private void validateSubmissionResponse(
			ConditionalOrderCreationResponse response,
			String clientOrderId) {
		if (response == null || response.conditionalOrderId() == null
				|| response.conditionalOrderId().isBlank()
				|| !clientOrderId.equals(response.clientOrderId())) {
			throw new OrderSubmissionException(
					"조건 주문 제출 응답 형식이 올바르지 않습니다.", true);
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

	/** 저장된 미리보기를 찾거나 찾을 수 없음 오류를 발생시킵니다. */
	private SingleConditionalOrderPreviewResponse findPreview(String previewId) {
		return previewStore.findById(previewId)
				.orElseThrow(() -> new OrderPreviewNotFoundException(
						"조건 주문 미리보기를 찾을 수 없습니다."));
	}

	/** 저장된 실행을 찾거나 내부 저장 오류를 발생시킵니다. */
	private SingleConditionalOrderExecutionResponse findExecution(String executionId) {
		return executionStore.findById(executionId)
				.orElseThrow(() -> new OrderExecutionSubmissionException(
						"조건 주문 실행 결과를 데이터베이스에서 찾지 못했습니다."));
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

	/** 예상 주문금액과 수수료 계산 결과를 함께 전달합니다. */
	private record Calculation(
			BigDecimal calculationPrice,
			BigDecimal commissionRate,
			BigDecimal orderAmount,
			BigDecimal commission,
			BigDecimal amountAfterCommission,
			boolean requiresHighValueConfirmation) {
	}
}
