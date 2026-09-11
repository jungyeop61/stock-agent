package com.jusika.backend.orderexecution;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.jusika.backend.brokersafety.BrokerOrderRiskSnapshot;
import com.jusika.backend.buyingpower.BuyingPowerResponse;
import com.jusika.backend.commission.CommissionsResponse;
import com.jusika.backend.commission.CommissionsResponse.CommissionItem;
import com.jusika.backend.order.OrderCreationResponse;
import com.jusika.backend.order.OrderTimeInForce;
import com.jusika.backend.order.QuantityOrderSubmissionRequest;
import com.jusika.backend.orderpreview.OrderPreviewException;
import com.jusika.backend.orderpreview.OrderPreviewExpiredException;
import com.jusika.backend.orderpreview.OrderPreviewNotFoundException;
import com.jusika.backend.orderpreview.OrderPreviewResponse;
import com.jusika.backend.orderpreview.OrderPreviewStatus;
import com.jusika.backend.orderpreview.OrderPreviewStore;
import com.jusika.backend.orderpreview.OrderSide;
import com.jusika.backend.orderpreview.OrderType;
import com.jusika.backend.sellablequantity.SellableQuantityResponse;
import com.jusika.backend.stock.StockPriceResponse;
import com.jusika.backend.toss.market.TossPriceClient;
import com.jusika.backend.toss.orderinfo.TossBuyingPowerClient;
import com.jusika.backend.toss.orderinfo.TossCommissionsClient;
import com.jusika.backend.toss.orderinfo.TossSellableQuantityClient;

/**
 * 승인된 주문 미리보기를 최종 재검증하고 중복 없이 현재 주문 모드로 제출합니다.
 */
@Service
public class OrderExecutionService {

	private static final BigDecimal HIGH_VALUE_KRW_THRESHOLD = new BigDecimal("100000000");
	private static final BigDecimal MAX_COMMISSION_RATE = BigDecimal.ONE;

	private final OrderPreviewStore previewStore;
	private final OrderExecutionStore executionStore;
	private final OrderSubmissionGateway submissionGateway;
	private final OrderRequestFingerprint requestFingerprint;
	private final TossPriceClient priceClient;
	private final TossBuyingPowerClient buyingPowerClient;
	private final TossSellableQuantityClient sellableQuantityClient;
	private final TossCommissionsClient commissionsClient;
	private final Clock clock;

	/**
	 * 미리보기·실행 저장소, 주문 제출 경계와 최종 재검증용 조회 기능을 전달받습니다.
	 *
	 * @param previewStore 승인된 주문 미리보기 저장소
	 * @param executionStore 중복 실행을 막고 상태를 기록할 저장소
	 * @param submissionGateway 현재 설정된 모의 또는 실제 주문 제출 경계
	 * @param requestFingerprint 최초 제출 주문의 변경 감지용 지문 계산기
	 * @param priceClient 최종 현재가 조회 클라이언트
	 * @param buyingPowerClient 최종 매수 가능 금액 조회 클라이언트
	 * @param sellableQuantityClient 최종 매도 가능 수량 조회 클라이언트
	 * @param commissionsClient 최종 시장별 수수료 조회 클라이언트
	 * @param clock 실행 시각을 기록할 시스템 시계
	 */
	public OrderExecutionService(
			OrderPreviewStore previewStore,
			OrderExecutionStore executionStore,
			OrderSubmissionGateway submissionGateway,
			OrderRequestFingerprint requestFingerprint,
			TossPriceClient priceClient,
			TossBuyingPowerClient buyingPowerClient,
			TossSellableQuantityClient sellableQuantityClient,
			TossCommissionsClient commissionsClient,
			Clock clock) {
		this.previewStore = previewStore;
		this.executionStore = executionStore;
		this.submissionGateway = submissionGateway;
		this.requestFingerprint = requestFingerprint;
		this.priceClient = priceClient;
		this.buyingPowerClient = buyingPowerClient;
		this.sellableQuantityClient = sellableQuantityClient;
		this.commissionsClient = commissionsClient;
		this.clock = clock;
	}

	/**
	 * 승인된 미리보기 한 건을 최종 재검증하고 데이터베이스 실행권을 확보한 뒤 주문을 제출합니다.
	 * 기본 설정에서는 모의 게이트웨이만 호출하므로 실제 주문이 발생하지 않습니다.
	 *
	 * @param previewId 실행할 주문 미리보기 식별값
	 * @return 데이터베이스에 기록된 최종 주문 실행 상태
	 */
	public OrderExecutionResponse executeApprovedPreview(String previewId) {
		validatePreviewId(previewId);
		OrderPreviewResponse preview = findPreview(previewId);
		OffsetDateTime startedAt = OffsetDateTime.now(clock);
		validateExecutableState(preview, startedAt);
		submissionGateway.requireSubmissionAvailable(preview.accountSeq());
		BrokerOrderRiskSnapshot riskSnapshot = revalidateAccountConditions(preview);
		submissionGateway.requireOrderWithinLimits(riskSnapshot);

		String executionId = UUID.randomUUID().toString();
		String clientOrderId = UUID.randomUUID().toString();
		OrderExecutionResponse prepared = new OrderExecutionResponse(
				executionId,
				preview.previewId(),
				clientOrderId,
				submissionGateway.mode(),
				OrderExecutionStatus.PREPARED,
				null,
				null,
				startedAt,
				startedAt,
				null,
				null,
				null);

		QuantityOrderSubmissionRequest request = new QuantityOrderSubmissionRequest(
				clientOrderId,
				preview.symbol(),
				preview.side(),
				preview.orderType(),
				OrderTimeInForce.DAY,
				preview.quantity(),
				preview.requestedPrice(),
				preview.requiresHighValueConfirmation(),
				riskSnapshot);
		String fingerprint = requestFingerprint.calculate(preview.accountSeq(), request);
		if (!executionStore.claim(prepared, fingerprint)) {
			throw new OrderExecutionConflictException("이미 실행했거나 실행 중인 주문 미리보기입니다.");
		}
		if (!previewStore.consumeApproved(previewId, startedAt)) {
			executionStore.markPreparationFailed(executionId, startedAt);
			throw new OrderExecutionConflictException("주문 미리보기의 승인 상태가 변경되어 실행하지 못했습니다.");
		}
		if (!executionStore.markSubmitting(executionId, startedAt)) {
			executionStore.markPreparationFailed(executionId, startedAt);
			throw new OrderExecutionSubmissionException("주문 실행 상태를 제출 중으로 변경하지 못했습니다.");
		}

		return submitAndRecord(executionId, clientOrderId, preview.accountSeq(), request);
	}

	/**
	 * 실행 식별값으로 데이터베이스에 저장된 주문 실행 기록을 읽기 전용으로 조회합니다.
	 *
	 * @param executionId 우리 서버가 만든 주문 실행 식별값
	 * @return 저장된 주문 실행 기록
	 */
	public OrderExecutionResponse getExecution(String executionId) {
		validateExecutionId(executionId);
		return executionStore.findById(executionId)
				.orElseThrow(() -> new OrderExecutionNotFoundException(
						"주문 실행 기록을 찾을 수 없습니다."));
	}

	/**
	 * 주문 제출 결과를 접수·거절·불명 상태로 나눠 데이터베이스에 기록합니다.
	 *
	 * @param executionId 상태를 변경할 주문 실행 식별값
	 * @param clientOrderId 요청에 사용한 멱등성 식별값
	 * @param accountSeq 주문에 사용할 계좌 식별값
	 * @param request 최종 재검증을 마친 수량 주문
	 * @return 접수 상태로 저장된 주문 실행 응답
	 */
	private OrderExecutionResponse submitAndRecord(
			String executionId,
			String clientOrderId,
			long accountSeq,
			QuantityOrderSubmissionRequest request) {
		try {
			OrderCreationResponse submission = submissionGateway.submitQuantityOrder(accountSeq, request);
			validateSubmissionResponse(submission, clientOrderId);
			OffsetDateTime completedAt = OffsetDateTime.now(clock);
			if (!executionStore.markAccepted(executionId, submission.orderId(), completedAt)) {
				throw new OrderExecutionSubmissionException("주문 접수 결과를 데이터베이스에 기록하지 못했습니다.");
			}
			return findExecution(executionId);
		} catch (OrderSubmissionException exception) {
			OffsetDateTime failedAt = OffsetDateTime.now(clock);
			if (exception.isSubmissionStateUnknown()) {
				executionStore.markUnknown(executionId, failedAt);
				throw new OrderExecutionSubmissionException(
						"주문 접수 여부를 확인할 수 없습니다. 자동으로 다시 주문하지 마세요.");
			}
			executionStore.markRejected(executionId, failedAt);
			throw new OrderExecutionSubmissionException("증권사가 주문을 거절했습니다.");
		} catch (OrderExecutionSubmissionException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			OffsetDateTime failedAt = OffsetDateTime.now(clock);
			executionStore.markUnknown(executionId, failedAt);
			throw new OrderExecutionSubmissionException(
					"주문 접수 여부를 확인할 수 없습니다. 자동으로 다시 주문하지 마세요.");
		}
	}

	/**
	 * 제출 게이트웨이가 주문번호와 요청 멱등성 식별값을 정확히 반환했는지 확인합니다.
	 *
	 * @param submission 모의 또는 실제 증권사가 반환한 주문 생성 결과
	 * @param clientOrderId 요청에 사용한 멱등성 식별값
	 */
	private void validateSubmissionResponse(
			OrderCreationResponse submission,
			String clientOrderId) {
		if (submission == null
				|| submission.orderId() == null
				|| submission.orderId().isBlank()
				|| !clientOrderId.equals(submission.clientOrderId())) {
			throw new OrderSubmissionException("주문 제출 응답 형식이 올바르지 않습니다.", true);
		}
	}

	/**
	 * 승인 뒤 변할 수 있는 현재가, 수수료와 계좌의 금액 또는 수량을 다시 확인합니다.
	 *
	 * @param preview 사용자가 승인한 저장된 주문 미리보기
	 * @return 최종 현재가로 확정한 수량과 주문금액 한도 검사값
	 */
	private BrokerOrderRiskSnapshot revalidateAccountConditions(OrderPreviewResponse preview) {
		try {
			StockPriceResponse stockPrice = priceClient.getCurrentPrice(preview.symbol());
			validateStockIdentity(preview, stockPrice);
			BigDecimal calculationPrice = preview.orderType() == OrderType.LIMIT
					? preview.requestedPrice()
					: stockPrice.price();
			BigDecimal commissionRate = findCommissionRate(
					preview.accountSeq(), preview.marketCountry());
			BigDecimal orderAmount = calculationPrice.multiply(preview.quantity());

			if (preview.side() == OrderSide.BUY) {
				BigDecimal requiredAmount = orderAmount.add(orderAmount.multiply(commissionRate));
				BuyingPowerResponse buyingPower = buyingPowerClient.getBuyingPower(
						preview.accountSeq(), preview.currency());
				validateBuyingPower(preview, buyingPower);
				if (buyingPower.cashBuyingPower().compareTo(requiredAmount) < 0) {
					throw new OrderExecutionValidationException(
							"최종 확인 결과 매수 가능 금액이 부족합니다. 새 미리보기를 만들어 주세요.");
				}
			} else {
				SellableQuantityResponse sellable = sellableQuantityClient.getSellableQuantity(
						preview.accountSeq(), preview.symbol());
				validateSellableQuantity(preview, sellable);
				if (sellable.sellableQuantity().compareTo(preview.quantity()) < 0) {
					throw new OrderExecutionValidationException(
							"최종 확인 결과 매도 가능 수량이 부족합니다. 새 미리보기를 만들어 주세요.");
				}
			}

			if ("KRW".equals(preview.currency())
					&& orderAmount.compareTo(HIGH_VALUE_KRW_THRESHOLD) >= 0
					&& !preview.requiresHighValueConfirmation()) {
				throw new OrderExecutionValidationException(
						"최종 주문금액이 1억원 이상이므로 새 미리보기에서 다시 확인해야 합니다.");
			}
			return new BrokerOrderRiskSnapshot(
					preview.quantity(), orderAmount, preview.currency());
		} catch (OrderExecutionValidationException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			throw new OrderExecutionValidationException(
					"최종 주문 조건을 조회하지 못했습니다. 주문을 실행하지 않았습니다.");
		}
	}

	/**
	 * 매수 가능 금액 응답이 요청 계좌·통화와 일치하고 음수가 아닌지 확인합니다.
	 *
	 * @param preview 승인된 주문 미리보기
	 * @param buyingPower 최종 조회한 매수 가능 금액
	 */
	private void validateBuyingPower(
			OrderPreviewResponse preview,
			BuyingPowerResponse buyingPower) {
		if (buyingPower == null
				|| buyingPower.accountSeq() != preview.accountSeq()
				|| !preview.currency().equals(buyingPower.currency())
				|| buyingPower.cashBuyingPower() == null
				|| buyingPower.cashBuyingPower().signum() < 0) {
			throw new OrderExecutionValidationException(
					"최종 매수 가능 금액이 승인한 계좌 또는 통화와 일치하지 않습니다.");
		}
	}

	/**
	 * 매도 가능 수량 응답이 요청 계좌·종목과 일치하고 음수가 아닌지 확인합니다.
	 *
	 * @param preview 승인된 주문 미리보기
	 * @param sellable 최종 조회한 매도 가능 수량
	 */
	private void validateSellableQuantity(
			OrderPreviewResponse preview,
			SellableQuantityResponse sellable) {
		if (sellable == null
				|| sellable.accountSeq() != preview.accountSeq()
				|| sellable.symbol() == null
				|| !preview.symbol().equalsIgnoreCase(sellable.symbol())
				|| sellable.sellableQuantity() == null
				|| sellable.sellableQuantity().signum() < 0) {
			throw new OrderExecutionValidationException(
					"최종 매도 가능 수량이 승인한 계좌 또는 종목과 일치하지 않습니다.");
		}
	}

	/**
	 * 최종 현재가가 승인한 종목·통화와 일치하고 양수인지 확인합니다.
	 *
	 * @param preview 승인된 주문 미리보기
	 * @param stockPrice 최종 조회한 종목 현재가
	 */
	private void validateStockIdentity(
			OrderPreviewResponse preview,
			StockPriceResponse stockPrice) {
		String expectedMarketCountry = "KRW".equals(preview.currency()) ? "KR" : "US";
		if (stockPrice == null
				|| stockPrice.symbol() == null
				|| !preview.symbol().equalsIgnoreCase(stockPrice.symbol())
				|| stockPrice.currency() == null
				|| !preview.currency().equals(stockPrice.currency())
				|| !expectedMarketCountry.equals(preview.marketCountry())
				|| stockPrice.price() == null
				|| stockPrice.price().signum() <= 0) {
			throw new OrderExecutionValidationException(
					"최종 현재가가 승인한 주문 종목 또는 통화와 일치하지 않습니다.");
		}
	}

	/**
	 * 최종 수수료 목록에서 승인한 시장에 적용되는 안전한 수수료율을 찾습니다.
	 *
	 * @param accountSeq 주문에 사용할 계좌 식별값
	 * @param marketCountry 승인한 시장 국가 코드
	 * @return 0 이상 1 이하인 소수 비율 형태의 수수료율
	 */
	private BigDecimal findCommissionRate(long accountSeq, String marketCountry) {
		CommissionsResponse response = commissionsClient.getCommissions(accountSeq);
		List<CommissionItem> commissions = response == null ? null : response.commissions();
		if (response == null || response.accountSeq() != accountSeq || commissions == null) {
			throw new OrderExecutionValidationException("최종 매매 수수료 정보를 확인하지 못했습니다.");
		}
		CommissionItem commission = commissions.stream()
				.filter(item -> item != null && marketCountry.equals(item.marketCountry()))
				.findFirst()
				.orElseThrow(() -> new OrderExecutionValidationException(
						"최종 매매 수수료 정보를 확인하지 못했습니다."));
		if (commission.commissionRate() == null
				|| commission.commissionRate().signum() < 0
				|| commission.commissionRate().compareTo(MAX_COMMISSION_RATE) > 0) {
			throw new OrderExecutionValidationException("최종 매매 수수료율이 올바르지 않습니다.");
		}
		return commission.commissionRate();
	}

	/**
	 * 미리보기 식별값이 표준 UUID 문자열인지 확인합니다.
	 *
	 * @param previewId 검사할 주문 미리보기 식별값
	 */
	private void validatePreviewId(String previewId) {
		if (previewId == null) {
			throw new OrderPreviewException("주문 미리보기 식별값이 필요합니다.");
		}
		try {
			UUID.fromString(previewId);
		} catch (IllegalArgumentException exception) {
			throw new OrderPreviewException("주문 미리보기 식별값 형식이 올바르지 않습니다.");
		}
	}

	/**
	 * 주문 실행 식별값이 표준 UUID 문자열인지 확인합니다.
	 *
	 * @param executionId 검사할 주문 실행 식별값
	 */
	private void validateExecutionId(String executionId) {
		if (executionId == null) {
			throw new OrderExecutionRequestException("주문 실행 식별값이 필요합니다.");
		}
		try {
			UUID.fromString(executionId);
		} catch (IllegalArgumentException exception) {
			throw new OrderExecutionRequestException("주문 실행 식별값 형식이 올바르지 않습니다.");
		}
	}

	/**
	 * 저장된 미리보기를 찾고 없으면 안전한 찾을 수 없음 오류를 발생시킵니다.
	 *
	 * @param previewId 조회할 주문 미리보기 식별값
	 * @return 데이터베이스에 저장된 주문 미리보기
	 */
	private OrderPreviewResponse findPreview(String previewId) {
		return previewStore.findById(previewId)
				.orElseThrow(() -> new OrderPreviewNotFoundException("주문 미리보기를 찾을 수 없습니다."));
	}

	/**
	 * 미리보기가 승인 상태이며 실행 시점에도 유효한지 확인합니다.
	 *
	 * @param preview 실행하려는 주문 미리보기
	 * @param now 현재 실행 시각
	 */
	private void validateExecutableState(OrderPreviewResponse preview, OffsetDateTime now) {
		if (preview.status() != OrderPreviewStatus.APPROVED) {
			throw new OrderExecutionConflictException("승인된 주문 미리보기만 실행할 수 있습니다.");
		}
		if (!preview.expiresAt().isAfter(now)) {
			throw new OrderPreviewExpiredException(
					"주문 미리보기의 실행 시간이 지났습니다. 새 미리보기를 만들어 주세요.");
		}
	}

	/**
	 * 저장된 실행 기록을 찾고 없으면 내부 상태 오류를 발생시킵니다.
	 *
	 * @param executionId 조회할 주문 실행 식별값
	 * @return 데이터베이스에 저장된 주문 실행 응답
	 */
	private OrderExecutionResponse findExecution(String executionId) {
		return executionStore.findById(executionId)
				.orElseThrow(() -> new OrderExecutionSubmissionException(
						"주문 실행 결과를 데이터베이스에서 찾지 못했습니다."));
	}
}
