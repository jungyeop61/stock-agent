package com.jusika.backend.amountorderexecution;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.jusika.backend.amountorderpreview.AmountOrderPreviewExpiredException;
import com.jusika.backend.amountorderpreview.AmountOrderPreviewNotFoundException;
import com.jusika.backend.amountorderpreview.AmountOrderPreviewResponse;
import com.jusika.backend.amountorderpreview.AmountOrderPreviewStore;
import com.jusika.backend.amountorderwindow.UsAmountOrderWindowResponse;
import com.jusika.backend.amountorderwindow.UsAmountOrderWindowService;
import com.jusika.backend.amountorderwindow.UsAmountOrderWindowStatus;
import com.jusika.backend.buyingpower.BuyingPowerResponse;
import com.jusika.backend.commission.CommissionsResponse;
import com.jusika.backend.commission.CommissionsResponse.CommissionItem;
import com.jusika.backend.exchangerate.ExchangeRateResponse;
import com.jusika.backend.order.AmountOrderSubmissionRequest;
import com.jusika.backend.order.OrderCreationResponse;
import com.jusika.backend.orderexecution.OrderExecutionFailureType;
import com.jusika.backend.orderexecution.OrderExecutionStatus;
import com.jusika.backend.orderexecution.OrderSubmissionException;
import com.jusika.backend.orderpreview.OrderPreviewStatus;
import com.jusika.backend.orderpreview.OrderSide;
import com.jusika.backend.orderpreview.OrderType;
import com.jusika.backend.stock.StockPriceResponse;
import com.jusika.backend.toss.market.TossPriceClient;
import com.jusika.backend.toss.marketinfo.TossExchangeRateClient;
import com.jusika.backend.toss.orderinfo.TossBuyingPowerClient;
import com.jusika.backend.toss.orderinfo.TossCommissionsClient;

/**
 * 승인된 미국 주식 달러 금액 주문을 최종 재검증하고 MOCK 경계로 한 번만 제출합니다.
 */
@Service
public class AmountOrderExecutionService {

	private static final String CURRENCY_USD = "USD";
	private static final String CURRENCY_KRW = "KRW";
	private static final String MARKET_COUNTRY_US = "US";
	private static final BigDecimal HIGH_VALUE_KRW_THRESHOLD = new BigDecimal("100000000");
	private static final BigDecimal MAX_ORDER_KRW_AMOUNT = new BigDecimal("3000000000");
	private static final BigDecimal MAX_COMMISSION_RATE = BigDecimal.ONE;
	private static final int CALCULATION_SCALE = 18;

	private final AmountOrderPreviewStore previewStore;
	private final AmountOrderExecutionStore executionStore;
	private final AmountOrderSubmissionGateway submissionGateway;
	private final AmountOrderRequestFingerprint requestFingerprint;
	private final UsAmountOrderWindowService orderWindowService;
	private final TossPriceClient priceClient;
	private final TossBuyingPowerClient buyingPowerClient;
	private final TossCommissionsClient commissionsClient;
	private final TossExchangeRateClient exchangeRateClient;
	private final Clock clock;

	/**
	 * 금액 주문 실행에 필요한 저장소, MOCK 경계와 최신 조회 기능을 전달받습니다.
	 *
	 * @param previewStore 승인된 금액 주문 미리보기 저장소
	 * @param executionStore 중복 실행을 막고 결과를 기록할 저장소
	 * @param submissionGateway 실제 토스 클라이언트와 분리된 금액 주문 제출 경계
	 * @param requestFingerprint 최초 주문 내용의 비공개 지문 계산기
	 * @param orderWindowService 미국 금액 주문 접수 시간 판정 서비스
	 * @param priceClient 실행 직전 미국 주식 현재가 조회 클라이언트
	 * @param buyingPowerClient 실행 직전 달러 매수 가능 금액 조회 클라이언트
	 * @param commissionsClient 실행 직전 미국 시장 수수료 조회 클라이언트
	 * @param exchangeRateClient 실행 직전 달러 원화 환율 조회 클라이언트
	 * @param clock 실행 시각을 기록할 시스템 시계
	 */
	public AmountOrderExecutionService(
			AmountOrderPreviewStore previewStore,
			AmountOrderExecutionStore executionStore,
			AmountOrderSubmissionGateway submissionGateway,
			AmountOrderRequestFingerprint requestFingerprint,
			UsAmountOrderWindowService orderWindowService,
			TossPriceClient priceClient,
			TossBuyingPowerClient buyingPowerClient,
			TossCommissionsClient commissionsClient,
			TossExchangeRateClient exchangeRateClient,
			Clock clock) {
		this.previewStore = previewStore;
		this.executionStore = executionStore;
		this.submissionGateway = submissionGateway;
		this.requestFingerprint = requestFingerprint;
		this.orderWindowService = orderWindowService;
		this.priceClient = priceClient;
		this.buyingPowerClient = buyingPowerClient;
		this.commissionsClient = commissionsClient;
		this.exchangeRateClient = exchangeRateClient;
		this.clock = clock;
	}

	/**
	 * 승인된 금액 주문 미리보기를 최신 시장·계좌 조건으로 다시 검사하고 한 번만 MOCK 제출합니다.
	 * 실제 토스증권 금액 주문 클라이언트는 호출하지 않습니다.
	 *
	 * @param previewId 실행할 금액 주문 미리보기 식별값
	 * @return 데이터베이스에 기록된 최종 금액 주문 실행 상태
	 */
	public AmountOrderExecutionResponse executeApprovedPreview(String previewId) {
		validatePreviewId(previewId);
		AmountOrderPreviewResponse preview = findPreview(previewId);
		OffsetDateTime startedAt = OffsetDateTime.now(clock);
		validateExecutableState(preview, startedAt);
		validateImmutablePreview(preview);
		revalidateLatestConditions(preview, startedAt);
		String brokerMode = requireMockMode();

		String executionId = UUID.randomUUID().toString();
		String clientOrderId = UUID.randomUUID().toString();
		AmountOrderSubmissionRequest request = new AmountOrderSubmissionRequest(
				clientOrderId,
				preview.symbol(),
				OrderSide.BUY,
				preview.orderAmount(),
				preview.requiresHighValueConfirmation());
		AmountOrderExecutionResponse prepared = new AmountOrderExecutionResponse(
				executionId,
				preview.previewId(),
				clientOrderId,
				brokerMode,
				OrderExecutionStatus.PREPARED,
				null,
				null,
				startedAt,
				startedAt,
				null,
				null);
		String fingerprint = requestFingerprint.calculate(preview.accountSeq(), request);
		if (!executionStore.claim(prepared, fingerprint)) {
			throw new AmountOrderExecutionConflictException(
					"이미 실행했거나 실행 중인 금액 주문 미리보기입니다.");
		}
		if (!previewStore.consumeApproved(previewId, startedAt)) {
			executionStore.markPreparationFailed(executionId, startedAt);
			throw new AmountOrderExecutionConflictException(
					"금액 주문 미리보기의 승인 상태가 변경되어 실행하지 못했습니다.");
		}
		if (!executionStore.markSubmitting(executionId, startedAt)) {
			executionStore.markPreparationFailed(executionId, startedAt);
			throw new AmountOrderExecutionSubmissionException(
					"금액 주문 실행 상태를 제출 중으로 변경하지 못했습니다.");
		}
		return submitAndRecord(executionId, preview.accountSeq(), request);
	}

	/**
	 * 이번 단계의 금액 주문 실행 경계가 MOCK으로 고정되어 있는지 확인합니다.
	 *
	 * @return 안전한 모의 실행 모드 이름
	 */
	private String requireMockMode() {
		String mode = submissionGateway.mode();
		if (!"MOCK".equals(mode)) {
			throw new AmountOrderExecutionSubmissionException(
					"금액 주문 실행은 현재 MOCK 모드에서만 허용됩니다.");
		}
		return mode;
	}

	/**
	 * 실행 식별값으로 저장된 금액 주문 실행 결과를 읽기 전용으로 조회합니다.
	 *
	 * @param executionId 우리 서버가 만든 금액 주문 실행 식별값
	 * @return 저장된 금액 주문 실행 기록
	 */
	public AmountOrderExecutionResponse getExecution(String executionId) {
		validateExecutionId(executionId);
		return executionStore.findById(executionId)
				.orElseThrow(() -> new AmountOrderExecutionNotFoundException(
						"금액 주문 실행 기록을 찾을 수 없습니다."));
	}

	/**
	 * MOCK 제출 결과를 접수·거절·불명 상태로 나눠 데이터베이스에 기록합니다.
	 *
	 * @param executionId 상태를 변경할 금액 주문 실행 식별값
	 * @param accountSeq 주문에 사용할 계좌 식별값
	 * @param request 최종 재검증을 마친 금액 주문
	 * @return 접수 상태로 저장된 금액 주문 실행 결과
	 */
	private AmountOrderExecutionResponse submitAndRecord(
			String executionId,
			long accountSeq,
			AmountOrderSubmissionRequest request) {
		try {
			OrderCreationResponse submission = submissionGateway.submitAmountOrder(accountSeq, request);
			validateSubmissionResponse(submission, request.clientOrderId());
			OffsetDateTime completedAt = OffsetDateTime.now(clock);
			if (!executionStore.markAccepted(executionId, submission.orderId(), completedAt)) {
				throw new AmountOrderExecutionSubmissionException(
						"금액 주문 접수 결과를 데이터베이스에 기록하지 못했습니다.");
			}
			return findExecution(executionId);
		} catch (OrderSubmissionException exception) {
			OffsetDateTime failedAt = OffsetDateTime.now(clock);
			if (exception.isSubmissionStateUnknown()) {
				executionStore.markUnknown(executionId, failedAt);
				throw new AmountOrderExecutionSubmissionException(
						"금액 주문 접수 여부를 확인할 수 없습니다. 자동으로 다시 주문하지 마세요.");
			}
			executionStore.markRejected(executionId, failedAt);
			throw new AmountOrderExecutionSubmissionException("증권사가 금액 주문을 거절했습니다.");
		} catch (AmountOrderExecutionSubmissionException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			OffsetDateTime failedAt = OffsetDateTime.now(clock);
			executionStore.markUnknown(executionId, failedAt);
			throw new AmountOrderExecutionSubmissionException(
					"금액 주문 접수 여부를 확인할 수 없습니다. 자동으로 다시 주문하지 마세요.");
		}
	}

	/**
	 * 제출 경계가 주문번호와 요청 멱등성 식별값을 정확히 반환했는지 확인합니다.
	 *
	 * @param submission MOCK 제출 경계가 반환한 주문 생성 결과
	 * @param clientOrderId 요청에 사용한 멱등성 식별값
	 */
	private void validateSubmissionResponse(
			OrderCreationResponse submission,
			String clientOrderId) {
		if (submission == null
				|| submission.orderId() == null
				|| submission.orderId().isBlank()
				|| !clientOrderId.equals(submission.clientOrderId())) {
			throw new OrderSubmissionException("금액 주문 제출 응답 형식이 올바르지 않습니다.", true);
		}
	}

	/**
	 * 승인 뒤 변할 수 있는 장 운영시간, 현재가, 수수료, 매수 가능 금액과 환율을 다시 확인합니다.
	 *
	 * @param preview 사용자가 승인한 변경 불가 금액 주문 미리보기
	 * @param now 최종 재검증을 시작한 시각
	 */
	private void revalidateLatestConditions(
			AmountOrderPreviewResponse preview,
			OffsetDateTime now) {
		try {
			UsAmountOrderWindowResponse window = orderWindowService.checkCurrentWindow();
			if (window == null
					|| !window.businessDay()
					|| !window.orderable()
					|| window.status() != UsAmountOrderWindowStatus.OPEN) {
				throw new AmountOrderExecutionValidationException(
						"현재는 미국 주식 금액 주문 접수 시간이 아닙니다.");
			}

			StockPriceResponse stockPrice = priceClient.getCurrentPrice(preview.symbol());
			validateLatestPrice(preview, stockPrice);
			BigDecimal estimatedQuantity = preview.orderAmount().divide(
					stockPrice.price(), CALCULATION_SCALE, RoundingMode.DOWN);
			if (estimatedQuantity.signum() <= 0) {
				throw new AmountOrderExecutionValidationException(
						"최종 현재가 기준 예상 수량이 없어 새 미리보기가 필요합니다.");
			}

			BigDecimal commissionRate = findUsCommissionRate(preview.accountSeq());
			BigDecimal estimatedCommission = roundCalculation(
					preview.orderAmount().multiply(commissionRate), RoundingMode.HALF_UP);
			BigDecimal requiredAmount = preview.orderAmount().add(estimatedCommission);
			BuyingPowerResponse buyingPower = buyingPowerClient.getBuyingPower(
					preview.accountSeq(), CURRENCY_USD);
			validateBuyingPower(preview, buyingPower, requiredAmount);

			ExchangeRateResponse exchangeRate = exchangeRateClient.getExchangeRate(
					CURRENCY_USD, CURRENCY_KRW, null);
			validateCurrentExchangeRate(exchangeRate, now);
			BigDecimal currentOrderAmountKrw = roundCalculation(
					preview.orderAmount().multiply(exchangeRate.rate()), RoundingMode.HALF_UP);
			if (currentOrderAmountKrw.compareTo(MAX_ORDER_KRW_AMOUNT) > 0) {
				throw new AmountOrderExecutionValidationException(
						"최종 원화 환산 주문금액이 안전 한도를 넘어 새 미리보기가 필요합니다.");
			}
			if (currentOrderAmountKrw.compareTo(HIGH_VALUE_KRW_THRESHOLD) >= 0
					&& !preview.requiresHighValueConfirmation()) {
				throw new AmountOrderExecutionValidationException(
						"최종 원화 환산액이 1억원 이상이므로 새 미리보기에서 다시 확인해야 합니다.");
			}
		} catch (AmountOrderExecutionValidationException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			throw new AmountOrderExecutionValidationException(
					"최종 금액 주문 조건을 조회하지 못했습니다. 주문을 실행하지 않았습니다.");
		}
	}

	/**
	 * 승인한 미리보기의 고정 필드와 계산값이 서로 모순되지 않는지 확인합니다.
	 *
	 * @param preview 데이터베이스에서 읽은 승인된 금액 주문 미리보기
	 */
	private void validateImmutablePreview(AmountOrderPreviewResponse preview) {
		try {
			boolean validIdentity = preview.accountSeq() > 0
					&& preview.symbol() != null
					&& preview.symbol().matches("^[A-Z][A-Z0-9.\\-]*$")
					&& preview.side() == OrderSide.BUY
					&& preview.orderType() == OrderType.MARKET
					&& CURRENCY_USD.equals(preview.currency())
					&& MARKET_COUNTRY_US.equals(preview.marketCountry())
					&& preview.orderReady();
			if (!validIdentity
					|| !isPositive(preview.orderAmount())
					|| !isPositive(preview.referencePrice())
					|| !isPositive(preview.estimatedQuantity())
					|| preview.commissionRate() == null
					|| preview.commissionRate().signum() < 0
					|| preview.commissionRate().compareTo(MAX_COMMISSION_RATE) > 0
					|| preview.estimatedCommission() == null
					|| preview.estimatedCommission().signum() < 0
					|| !isPositive(preview.estimatedTotalCost())
					|| !isPositive(preview.exchangeRate())
					|| !isPositive(preview.estimatedOrderAmountKrw())
					|| preview.exchangeRateValidFrom() == null
					|| preview.exchangeRateValidUntil() == null
					|| preview.createdAt().isBefore(preview.exchangeRateValidFrom())
					|| !preview.createdAt().isBefore(preview.exchangeRateValidUntil())) {
				throw invalidPreview();
			}

			BigDecimal expectedQuantity = preview.orderAmount().divide(
					preview.referencePrice(), CALCULATION_SCALE, RoundingMode.DOWN).stripTrailingZeros();
			BigDecimal expectedCommission = roundCalculation(
					preview.orderAmount().multiply(preview.commissionRate()), RoundingMode.HALF_UP);
			BigDecimal expectedTotalCost = preview.orderAmount().add(expectedCommission);
			BigDecimal expectedKrwAmount = roundCalculation(
					preview.orderAmount().multiply(preview.exchangeRate()), RoundingMode.HALF_UP);
			boolean expectedHighValue = expectedKrwAmount.compareTo(HIGH_VALUE_KRW_THRESHOLD) >= 0;
			if (expectedQuantity.compareTo(preview.estimatedQuantity()) != 0
					|| expectedCommission.compareTo(preview.estimatedCommission()) != 0
					|| expectedTotalCost.compareTo(preview.estimatedTotalCost()) != 0
					|| expectedKrwAmount.compareTo(preview.estimatedOrderAmountKrw()) != 0
					|| expectedHighValue != preview.requiresHighValueConfirmation()) {
				throw invalidPreview();
			}
		} catch (AmountOrderExecutionValidationException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			throw invalidPreview();
		}
	}

	/**
	 * 현재가가 승인한 미국 종목과 달러 통화에 정확히 대응하는 양수인지 확인합니다.
	 *
	 * @param preview 승인한 금액 주문 미리보기
	 * @param stockPrice 실행 직전 현재가 조회 결과
	 */
	private void validateLatestPrice(
			AmountOrderPreviewResponse preview,
			StockPriceResponse stockPrice) {
		if (stockPrice == null
				|| stockPrice.symbol() == null
				|| !preview.symbol().equalsIgnoreCase(stockPrice.symbol())
				|| !CURRENCY_USD.equals(stockPrice.currency())
				|| stockPrice.price() == null
				|| stockPrice.price().signum() <= 0) {
			throw new AmountOrderExecutionValidationException(
					"최종 현재가가 승인한 미국 종목 또는 통화와 일치하지 않습니다.");
		}
	}

	/**
	 * 최신 수수료 목록에서 안전한 미국 시장 수수료율을 찾습니다.
	 *
	 * @param accountSeq 주문에 사용할 계좌 식별값
	 * @return 0 이상 1 이하인 미국 시장 수수료율
	 */
	private BigDecimal findUsCommissionRate(long accountSeq) {
		CommissionsResponse response = commissionsClient.getCommissions(accountSeq);
		List<CommissionItem> commissions = response == null ? null : response.commissions();
		if (response == null || response.accountSeq() != accountSeq || commissions == null) {
			throw new AmountOrderExecutionValidationException(
					"최종 미국 시장 수수료를 확인하지 못했습니다.");
		}
		CommissionItem commission = commissions.stream()
				.filter(item -> item != null && MARKET_COUNTRY_US.equals(item.marketCountry()))
				.findFirst()
				.orElseThrow(() -> new AmountOrderExecutionValidationException(
						"최종 미국 시장 수수료를 확인하지 못했습니다."));
		if (commission.commissionRate() == null
				|| commission.commissionRate().signum() < 0
				|| commission.commissionRate().compareTo(MAX_COMMISSION_RATE) > 0) {
			throw new AmountOrderExecutionValidationException(
					"최종 미국 시장 수수료율이 올바르지 않습니다.");
		}
		return commission.commissionRate();
	}

	/**
	 * 최신 달러 현금 매수 가능 금액이 수수료를 포함한 필요 금액 이상인지 확인합니다.
	 *
	 * @param preview 승인한 금액 주문 미리보기
	 * @param buyingPower 실행 직전 매수 가능 금액 조회 결과
	 * @param requiredAmount 최신 수수료를 포함한 달러 필요 금액
	 */
	private void validateBuyingPower(
			AmountOrderPreviewResponse preview,
			BuyingPowerResponse buyingPower,
			BigDecimal requiredAmount) {
		if (buyingPower == null
				|| buyingPower.accountSeq() != preview.accountSeq()
				|| !CURRENCY_USD.equals(buyingPower.currency())
				|| buyingPower.cashBuyingPower() == null
				|| buyingPower.cashBuyingPower().signum() < 0) {
			throw new AmountOrderExecutionValidationException(
					"최종 달러 매수 가능 금액이 승인한 계좌 또는 통화와 일치하지 않습니다.");
		}
		if (buyingPower.cashBuyingPower().compareTo(requiredAmount) < 0) {
			throw new AmountOrderExecutionValidationException(
					"최종 달러 매수 가능 금액이 부족합니다. 새 미리보기를 만들어 주세요.");
		}
	}

	/**
	 * 실행 시각에 유효한 USD→KRW 양수 환율인지 확인합니다.
	 *
	 * @param exchangeRate 실행 직전 환율 조회 결과
	 * @param now 실행 직전 현재 시각
	 */
	private void validateCurrentExchangeRate(
			ExchangeRateResponse exchangeRate,
			OffsetDateTime now) {
		if (exchangeRate == null
				|| !CURRENCY_USD.equals(exchangeRate.baseCurrency())
				|| !CURRENCY_KRW.equals(exchangeRate.quoteCurrency())
				|| !isPositive(exchangeRate.rate())
				|| exchangeRate.validFrom() == null
				|| exchangeRate.validUntil() == null
				|| now.isBefore(exchangeRate.validFrom())
				|| !now.isBefore(exchangeRate.validUntil())) {
			throw new AmountOrderExecutionValidationException(
					"실행 시각에 유효한 달러 원화 참고 환율이 필요합니다.");
		}
	}

	/**
	 * 계산값을 데이터베이스와 비교 가능한 소수점 18자리 이내로 정리합니다.
	 *
	 * @param value 정밀도를 정리할 계산 결과
	 * @param roundingMode 계산 목적에 사용할 반올림 방식
	 * @return 소수점 18자리 이내로 정리된 계산 결과
	 */
	private BigDecimal roundCalculation(BigDecimal value, RoundingMode roundingMode) {
		return (value.scale() > CALCULATION_SCALE
				? value.setScale(CALCULATION_SCALE, roundingMode)
				: value).stripTrailingZeros();
	}

	/**
	 * 숫자가 null이 아니고 0보다 큰지 확인합니다.
	 *
	 * @param value 검사할 숫자
	 * @return 양수이면 true
	 */
	private boolean isPositive(BigDecimal value) {
		return value != null && value.signum() > 0;
	}

	/**
	 * 승인한 금액 주문 미리보기의 고정값이 모순될 때 사용할 안전한 오류를 만듭니다.
	 *
	 * @return 새 미리보기를 요구하는 최종 재검증 오류
	 */
	private AmountOrderExecutionValidationException invalidPreview() {
		return new AmountOrderExecutionValidationException(
				"승인한 금액 주문 미리보기의 고정값이 올바르지 않습니다. 새 미리보기를 만들어 주세요.");
	}

	/**
	 * 미리보기가 승인 상태이며 실행 시점에도 유효한지 확인합니다.
	 *
	 * @param preview 실행하려는 금액 주문 미리보기
	 * @param now 현재 실행 시각
	 */
	private void validateExecutableState(
			AmountOrderPreviewResponse preview,
			OffsetDateTime now) {
		if (preview.status() != OrderPreviewStatus.APPROVED) {
			throw new AmountOrderExecutionConflictException(
					"승인된 금액 주문 미리보기만 실행할 수 있습니다.");
		}
		if (!preview.expiresAt().isAfter(now)) {
			throw new AmountOrderPreviewExpiredException(
					"금액 주문 미리보기의 실행 시간이 지났습니다. 새 미리보기를 만들어 주세요.");
		}
	}

	/**
	 * 미리보기 식별값이 표준 UUID 문자열인지 확인합니다.
	 *
	 * @param previewId 검사할 금액 주문 미리보기 식별값
	 */
	private void validatePreviewId(String previewId) {
		if (previewId == null) {
			throw new AmountOrderExecutionRequestException("금액 주문 미리보기 식별값이 필요합니다.");
		}
		try {
			UUID.fromString(previewId);
		} catch (IllegalArgumentException exception) {
			throw new AmountOrderExecutionRequestException(
					"금액 주문 미리보기 식별값 형식이 올바르지 않습니다.");
		}
	}

	/**
	 * 실행 식별값이 표준 UUID 문자열인지 확인합니다.
	 *
	 * @param executionId 검사할 금액 주문 실행 식별값
	 */
	private void validateExecutionId(String executionId) {
		if (executionId == null) {
			throw new AmountOrderExecutionRequestException("금액 주문 실행 식별값이 필요합니다.");
		}
		try {
			UUID.fromString(executionId);
		} catch (IllegalArgumentException exception) {
			throw new AmountOrderExecutionRequestException(
					"금액 주문 실행 식별값 형식이 올바르지 않습니다.");
		}
	}

	/**
	 * 저장된 금액 주문 미리보기를 찾고 없으면 안전한 찾을 수 없음 오류를 발생시킵니다.
	 *
	 * @param previewId 조회할 금액 주문 미리보기 식별값
	 * @return 데이터베이스에 저장된 금액 주문 미리보기
	 */
	private AmountOrderPreviewResponse findPreview(String previewId) {
		return previewStore.findById(previewId)
				.orElseThrow(() -> new AmountOrderPreviewNotFoundException(
						"금액 주문 미리보기를 찾을 수 없습니다."));
	}

	/**
	 * 저장된 금액 주문 실행 기록을 찾고 없으면 내부 상태 오류를 발생시킵니다.
	 *
	 * @param executionId 조회할 실행 식별값
	 * @return 데이터베이스에 저장된 금액 주문 실행 응답
	 */
	private AmountOrderExecutionResponse findExecution(String executionId) {
		return executionStore.findById(executionId)
				.orElseThrow(() -> new AmountOrderExecutionSubmissionException(
						"금액 주문 실행 결과를 데이터베이스에서 찾지 못했습니다."));
	}
}
