package com.jusika.backend.amountorderpreview;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.jusika.backend.buyingpower.BuyingPowerResponse;
import com.jusika.backend.commission.CommissionsResponse;
import com.jusika.backend.commission.CommissionsResponse.CommissionItem;
import com.jusika.backend.exchangerate.ExchangeRateResponse;
import com.jusika.backend.orderpreview.OrderPreviewProperties;
import com.jusika.backend.orderpreview.OrderPreviewStatus;
import com.jusika.backend.orderpreview.OrderSide;
import com.jusika.backend.orderpreview.OrderType;
import com.jusika.backend.stock.StockPriceResponse;
import com.jusika.backend.toss.market.TossPriceClient;
import com.jusika.backend.toss.marketinfo.TossExchangeRateClient;
import com.jusika.backend.toss.orderinfo.TossBuyingPowerClient;
import com.jusika.backend.toss.orderinfo.TossCommissionsClient;

/**
 * 실제 주문 없이 미국 주식 달러 금액 매수의 입력과 계좌 여력을 검증해 미리보기를 만듭니다.
 */
@Service
public class AmountOrderPreviewService {

	private static final String CURRENCY_USD = "USD";
	private static final String CURRENCY_KRW = "KRW";
	private static final String MARKET_COUNTRY_US = "US";
	private static final BigDecimal HIGH_VALUE_KRW_THRESHOLD = new BigDecimal("100000000");
	private static final BigDecimal MAX_ORDER_KRW_AMOUNT = new BigDecimal("3000000000");
	private static final BigDecimal MAX_COMMISSION_RATE = BigDecimal.ONE;
	private static final int CALCULATION_SCALE = 18;

	private final TossPriceClient priceClient;
	private final TossBuyingPowerClient buyingPowerClient;
	private final TossCommissionsClient commissionsClient;
	private final TossExchangeRateClient exchangeRateClient;
	private final AmountOrderPreviewStore previewStore;
	private final OrderPreviewProperties properties;
	private final Clock clock;

	/**
	 * 금액 주문 미리보기에 필요한 조회 기능과 저장소, 유효시간 설정과 시계를 전달받습니다.
	 *
	 * @param priceClient 미국 종목 현재가 조회 클라이언트
	 * @param buyingPowerClient 달러 매수 가능 금액 조회 클라이언트
	 * @param commissionsClient 미국 시장 수수료 조회 클라이언트
	 * @param exchangeRateClient 달러 원화 참고 환율 조회 클라이언트
	 * @param previewStore 계산을 마친 금액 주문 미리보기 저장소
	 * @param properties 미리보기 유효시간 설정
	 * @param clock 미리보기 생성 시각을 기록할 시스템 시계
	 */
	public AmountOrderPreviewService(
			TossPriceClient priceClient,
			TossBuyingPowerClient buyingPowerClient,
			TossCommissionsClient commissionsClient,
			TossExchangeRateClient exchangeRateClient,
			AmountOrderPreviewStore previewStore,
			OrderPreviewProperties properties,
			Clock clock) {
		this.priceClient = priceClient;
		this.buyingPowerClient = buyingPowerClient;
		this.commissionsClient = commissionsClient;
		this.exchangeRateClient = exchangeRateClient;
		this.previewStore = previewStore;
		this.properties = properties;
		this.clock = clock;
	}

	/**
	 * 미국 주식 달러 금액 시장가 매수의 예상 수량과 비용을 계산하고 변경 불가 미리보기를 저장합니다.
	 * 이 함수는 토스증권 주문 생성 API를 호출하지 않습니다.
	 *
	 * @param request 미리보기를 만들 계좌, 미국 종목과 달러 금액
	 * @return 실제 주문 없이 검증하고 저장한 금액 주문 미리보기
	 */
	public AmountOrderPreviewResponse createPreview(AmountOrderPreviewRequest request) {
		validateRequest(request);
		String normalizedSymbol = request.symbol().toUpperCase(Locale.ROOT);

		StockPriceResponse stockPrice = priceClient.getCurrentPrice(normalizedSymbol);
		validateUsStockPrice(stockPrice, normalizedSymbol);
		BigDecimal commissionRate = findUsCommissionRate(request.accountSeq());
		BigDecimal estimatedQuantity = divideForEstimate(request.orderAmount(), stockPrice.price());
		BigDecimal estimatedCommission = roundCalculation(
				request.orderAmount().multiply(commissionRate), RoundingMode.HALF_UP);
		BigDecimal estimatedTotalCost = request.orderAmount().add(estimatedCommission);
		validateStoredDecimal(estimatedTotalCost, 30, "예상 총 필요 금액");
		validateBuyingPower(request.accountSeq(), estimatedTotalCost);

		ExchangeRateResponse exchangeRate = exchangeRateClient.getExchangeRate(
				CURRENCY_USD, CURRENCY_KRW, null);
		OffsetDateTime createdAt = OffsetDateTime.now(clock);
		validateCurrentExchangeRate(exchangeRate, createdAt);
		BigDecimal estimatedOrderAmountKrw = roundCalculation(
				request.orderAmount().multiply(exchangeRate.rate()), RoundingMode.HALF_UP);
		if (estimatedOrderAmountKrw.compareTo(MAX_ORDER_KRW_AMOUNT) > 0) {
			throw new AmountOrderPreviewException("원화 환산 주문금액은 30억원을 초과할 수 없습니다.");
		}

		AmountOrderPreviewResponse preview = new AmountOrderPreviewResponse(
				UUID.randomUUID().toString(),
				createdAt,
				createdAt.plus(properties.expiration()),
				request.accountSeq(),
				normalizedSymbol,
				OrderSide.BUY,
				OrderType.MARKET,
				request.orderAmount(),
				CURRENCY_USD,
				MARKET_COUNTRY_US,
				stockPrice.price(),
				estimatedQuantity,
				commissionRate,
				estimatedCommission,
				estimatedTotalCost,
				exchangeRate.rate(),
				exchangeRate.validFrom(),
				exchangeRate.validUntil(),
				estimatedOrderAmountKrw,
				estimatedOrderAmountKrw.compareTo(HIGH_VALUE_KRW_THRESHOLD) >= 0,
				true,
				OrderPreviewStatus.PENDING_APPROVAL);
		return previewStore.save(preview);
	}

	/**
	 * 식별값으로 저장된 금액 주문 미리보기의 변경 불가 계산 결과를 조회합니다.
	 *
	 * @param previewId 조회할 금액 주문 미리보기 식별값
	 * @return 데이터베이스에 저장된 금액 주문 미리보기
	 */
	public AmountOrderPreviewResponse getPreview(String previewId) {
		validatePreviewId(previewId);
		return previewStore.findById(previewId)
				.orElseThrow(() -> new AmountOrderPreviewNotFoundException(
						"금액 주문 미리보기를 찾을 수 없습니다."));
	}

	/**
	 * 외부 조회 전에 계좌, 미국 종목 코드와 양수 달러 금액 형식을 검사합니다.
	 *
	 * @param request 검사할 금액 주문 미리보기 요청
	 */
	private void validateRequest(AmountOrderPreviewRequest request) {
		if (request == null) {
			throw new AmountOrderPreviewException("금액 주문 미리보기 요청이 필요합니다.");
		}
		if (request.accountSeq() <= 0) {
			throw new AmountOrderPreviewException("계좌 식별값은 1 이상이어야 합니다.");
		}
		if (request.symbol() == null
				|| request.symbol().length() > 30
				|| !request.symbol().matches("^[A-Za-z][A-Za-z0-9.\\-]*$")) {
			throw new AmountOrderPreviewException("금액 주문에는 올바른 미국 주식 종목 코드가 필요합니다.");
		}
		if (request.orderAmount() == null || request.orderAmount().signum() <= 0) {
			throw new AmountOrderPreviewException("달러 주문 금액은 0보다 커야 합니다.");
		}
		if (request.orderAmount().toPlainString().length() > 30) {
			throw new AmountOrderPreviewException("달러 주문 금액은 30자 이하여야 합니다.");
		}
		validateStoredDecimal(request.orderAmount(), 30, "달러 주문 금액");
	}

	/**
	 * 현재가가 요청한 미국 종목의 양수 달러 가격인지 확인합니다.
	 *
	 * @param stockPrice 현재가 조회 결과
	 * @param requestedSymbol 정규화된 요청 종목 코드
	 */
	private void validateUsStockPrice(StockPriceResponse stockPrice, String requestedSymbol) {
		if (stockPrice == null
				|| stockPrice.symbol() == null
				|| !requestedSymbol.equalsIgnoreCase(stockPrice.symbol())
				|| stockPrice.price() == null
				|| stockPrice.price().signum() <= 0
				|| !CURRENCY_USD.equals(stockPrice.currency())) {
			throw new AmountOrderPreviewException("금액 주문은 미국 주식의 올바른 달러 현재가가 필요합니다.");
		}
		validateStoredDecimal(stockPrice.price(), CALCULATION_SCALE, "미국 주식 현재가");
	}

	/**
	 * 계좌 수수료 목록에서 안전한 미국 시장 수수료율을 찾습니다.
	 *
	 * @param accountSeq 주문에 사용할 계좌 식별값
	 * @return 0 이상 1 이하인 미국 시장 수수료율
	 */
	private BigDecimal findUsCommissionRate(long accountSeq) {
		CommissionsResponse response = commissionsClient.getCommissions(accountSeq);
		List<CommissionItem> commissions = response == null ? null : response.commissions();
		if (response == null || response.accountSeq() != accountSeq || commissions == null) {
			throw new AmountOrderPreviewException("미국 시장의 매매 수수료를 찾지 못했습니다.");
		}
		CommissionItem commission = commissions.stream()
				.filter(item -> item != null && MARKET_COUNTRY_US.equals(item.marketCountry()))
				.findFirst()
				.orElseThrow(() -> new AmountOrderPreviewException(
						"미국 시장의 매매 수수료를 찾지 못했습니다."));
		if (commission.commissionRate() == null
				|| commission.commissionRate().signum() < 0
				|| commission.commissionRate().compareTo(MAX_COMMISSION_RATE) > 0) {
			throw new AmountOrderPreviewException("계산에 사용할 미국 시장 수수료율이 올바르지 않습니다.");
		}
		validateStoredDecimal(commission.commissionRate(), CALCULATION_SCALE, "미국 시장 수수료율");
		return commission.commissionRate();
	}

	/**
	 * 수수료를 포함한 예상 달러 필요 금액이 현금 매수 가능 금액 이내인지 확인합니다.
	 *
	 * @param accountSeq 주문에 사용할 계좌 식별값
	 * @param requiredAmount 수수료를 포함한 예상 달러 필요 금액
	 */
	private void validateBuyingPower(long accountSeq, BigDecimal requiredAmount) {
		BuyingPowerResponse response = buyingPowerClient.getBuyingPower(accountSeq, CURRENCY_USD);
		if (response == null
				|| response.accountSeq() != accountSeq
				|| !CURRENCY_USD.equals(response.currency())
				|| response.cashBuyingPower() == null
				|| response.cashBuyingPower().signum() < 0) {
			throw new AmountOrderPreviewException("달러 매수 가능 금액 응답이 올바르지 않습니다.");
		}
		if (response.cashBuyingPower().compareTo(requiredAmount) < 0) {
			throw new AmountOrderPreviewException("달러 매수 가능 금액이 부족합니다.");
		}
	}

	/**
	 * 환율이 USD→KRW 방향이고 미리보기 생성 시각에 유효한 양수 값인지 확인합니다.
	 *
	 * @param exchangeRate 환율 조회 결과
	 * @param createdAt 미리보기를 생성하는 현재 시각
	 */
	private void validateCurrentExchangeRate(
			ExchangeRateResponse exchangeRate,
			OffsetDateTime createdAt) {
		if (exchangeRate == null
				|| !CURRENCY_USD.equals(exchangeRate.baseCurrency())
				|| !CURRENCY_KRW.equals(exchangeRate.quoteCurrency())
				|| exchangeRate.rate() == null
				|| exchangeRate.rate().signum() <= 0
				|| exchangeRate.validFrom() == null
				|| exchangeRate.validUntil() == null
				|| createdAt.isBefore(exchangeRate.validFrom())
				|| !createdAt.isBefore(exchangeRate.validUntil())) {
			throw new AmountOrderPreviewException("현재 유효한 달러 원화 참고 환율이 필요합니다.");
		}
		validateStoredDecimal(exchangeRate.rate(), CALCULATION_SCALE, "달러 원화 참고 환율");
	}

	/**
	 * 달러 주문 금액을 현재가로 나눠 참고용 예상 수량을 소수점 18자리까지 계산합니다.
	 *
	 * @param orderAmount 달러 주문 금액
	 * @param referencePrice 미국 주식 현재가
	 * @return 실제 체결 수량을 보장하지 않는 참고용 예상 수량
	 */
	private BigDecimal divideForEstimate(BigDecimal orderAmount, BigDecimal referencePrice) {
		BigDecimal estimatedQuantity = orderAmount.divide(
				referencePrice, CALCULATION_SCALE, RoundingMode.DOWN);
		if (estimatedQuantity.signum() <= 0) {
			throw new AmountOrderPreviewException("달러 주문 금액이 현재가 기준 최소 예상 수량보다 작습니다.");
		}
		return estimatedQuantity.stripTrailingZeros();
	}

	/**
	 * 곱셈 계산 결과를 데이터베이스 계산 정밀도 안으로 반올림하고 범위를 확인합니다.
	 *
	 * @param value 정밀도를 정리할 계산 결과
	 * @param roundingMode 계산 목적에 사용할 반올림 방식
	 * @return 소수점 18자리 이내로 정리된 계산 결과
	 */
	private BigDecimal roundCalculation(BigDecimal value, RoundingMode roundingMode) {
		BigDecimal rounded = value.scale() > CALCULATION_SCALE
				? value.setScale(CALCULATION_SCALE, roundingMode)
				: value;
		validateStoredDecimal(rounded, CALCULATION_SCALE, "금액 주문 계산 결과");
		return rounded.stripTrailingZeros();
	}

	/**
	 * 숫자가 데이터베이스 정밀도와 허용 소수 자릿수 안에 들어오는지 확인합니다.
	 *
	 * @param value 검사할 숫자
	 * @param maximumScale 허용할 최대 소수 자릿수
	 * @param fieldName 오류 메시지에 사용할 항목 이름
	 */
	private void validateStoredDecimal(BigDecimal value, int maximumScale, String fieldName) {
		BigDecimal normalized = value.stripTrailingZeros();
		int scale = Math.max(normalized.scale(), 0);
		int integerDigits = Math.max(normalized.precision() - normalized.scale(), 0);
		if (scale > maximumScale || integerDigits + maximumScale > 65) {
			throw new AmountOrderPreviewException(fieldName + "의 숫자 범위가 너무 큽니다.");
		}
	}

	/**
	 * 조회 URL에 들어온 미리보기 식별값이 표준 UUID 문자열인지 확인합니다.
	 *
	 * @param previewId 검사할 금액 주문 미리보기 식별값
	 */
	private void validatePreviewId(String previewId) {
		if (previewId == null) {
			throw new AmountOrderPreviewException("금액 주문 미리보기 식별값이 필요합니다.");
		}
		try {
			UUID.fromString(previewId);
		} catch (IllegalArgumentException exception) {
			throw new AmountOrderPreviewException("금액 주문 미리보기 식별값 형식이 올바르지 않습니다.");
		}
	}
}
