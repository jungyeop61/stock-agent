package com.jusika.backend.orderpreview;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.jusika.backend.buyingpower.BuyingPowerResponse;
import com.jusika.backend.commission.CommissionsResponse;
import com.jusika.backend.commission.CommissionsResponse.CommissionItem;
import com.jusika.backend.sellablequantity.SellableQuantityResponse;
import com.jusika.backend.stock.StockPriceResponse;
import com.jusika.backend.toss.market.TossPriceClient;
import com.jusika.backend.toss.orderinfo.TossBuyingPowerClient;
import com.jusika.backend.toss.orderinfo.TossCommissionsClient;
import com.jusika.backend.toss.orderinfo.TossSellableQuantityClient;

/**
 * 실제 주문을 전송하지 않고 주문 규칙과 계좌 여력을 검사해 예상 금액을 계산합니다.
 */
@Service
public class OrderPreviewService {

	private static final BigDecimal HIGH_VALUE_KRW_THRESHOLD = new BigDecimal("100000000");
	private static final BigDecimal MAX_COMMISSION_RATE = BigDecimal.ONE;

	private final TossPriceClient priceClient;
	private final TossBuyingPowerClient buyingPowerClient;
	private final TossSellableQuantityClient sellableQuantityClient;
	private final TossCommissionsClient commissionsClient;
	private final OrderPreviewStore previewStore;
	private final OrderPreviewProperties properties;
	private final Clock clock;

	/**
	 * 미리보기에 필요한 현재가, 계좌 여력, 수수료 조회 기능과 시스템 시계를 전달받습니다.
	 *
	 * @param priceClient 종목 현재가 조회 클라이언트
	 * @param buyingPowerClient 통화별 매수 가능 금액 조회 클라이언트
	 * @param sellableQuantityClient 종목별 매도 가능 수량 조회 클라이언트
	 * @param commissionsClient 계좌의 시장별 수수료 조회 클라이언트
	 * @param previewStore 계산을 마친 미리보기와 승인 상태를 보관할 저장소
	 * @param properties 미리보기 승인 유효시간 설정
	 * @param clock 미리보기 생성 시각을 기록할 시스템 시계
	 */
	public OrderPreviewService(
			TossPriceClient priceClient,
			TossBuyingPowerClient buyingPowerClient,
			TossSellableQuantityClient sellableQuantityClient,
			TossCommissionsClient commissionsClient,
			OrderPreviewStore previewStore,
			OrderPreviewProperties properties,
			Clock clock) {
		this.priceClient = priceClient;
		this.buyingPowerClient = buyingPowerClient;
		this.sellableQuantityClient = sellableQuantityClient;
		this.commissionsClient = commissionsClient;
		this.previewStore = previewStore;
		this.properties = properties;
		this.clock = clock;
	}

	/**
	 * 수량 기반 주문의 형식과 계좌 주문 가능 조건을 확인하고 예상 금액을 반환합니다.
	 * 이 함수는 토스증권 주문 생성 API를 호출하지 않습니다.
	 *
	 * @param request 미리보기를 만들 주문 내용
	 * @return 실제 주문 없이 계산한 주문 미리보기
	 * @throws OrderPreviewException 주문 규칙이나 계좌 주문 가능 조건을 통과하지 못한 경우
	 */
	public OrderPreviewResponse createPreview(OrderPreviewRequest request) {
		validateBasicRequest(request);
		String normalizedSymbol = request.symbol().toUpperCase(Locale.ROOT);

		StockPriceResponse stockPrice = priceClient.getCurrentPrice(normalizedSymbol);
		Market market = resolveMarket(stockPrice);
		validateQuantity(request.quantity(), request.side(), request.orderType(), market);
		validatePrice(request.price(), request.orderType(), market);

		BigDecimal calculationPrice = request.orderType() == OrderType.LIMIT
				? request.price()
				: stockPrice.price();
		BigDecimal estimatedOrderAmount = calculationPrice.multiply(request.quantity());

		if (request.side() == OrderSide.SELL) {
			validateSellableQuantity(request.accountSeq(), normalizedSymbol, request.quantity());
		}

		BigDecimal commissionRate = findCommissionRate(request.accountSeq(), market.marketCountry());
		BigDecimal estimatedCommission = estimatedOrderAmount.multiply(commissionRate);
		BigDecimal estimatedAmountAfterCommission = request.side() == OrderSide.BUY
				? estimatedOrderAmount.add(estimatedCommission)
				: estimatedOrderAmount.subtract(estimatedCommission);

		if (request.side() == OrderSide.BUY) {
			validateBuyingPower(request.accountSeq(), market.currency(), estimatedAmountAfterCommission);
		}

		OffsetDateTime createdAt = OffsetDateTime.now(clock);
		OrderPreviewResponse preview = new OrderPreviewResponse(
				UUID.randomUUID().toString(),
				createdAt,
				createdAt.plus(properties.expiration()),
				request.accountSeq(),
				stockPrice.symbol(),
				request.side(),
				request.orderType(),
				request.quantity(),
				request.price(),
				stockPrice.price(),
				calculationPrice,
				market.currency(),
				market.marketCountry(),
				commissionRate,
				estimatedOrderAmount,
				estimatedCommission,
				estimatedAmountAfterCommission,
				request.side() == OrderSide.SELL,
				requiresHighValueConfirmation(market.currency(), estimatedOrderAmount),
				true,
				OrderPreviewStatus.PENDING_APPROVAL,
				null);
		return previewStore.save(preview);
	}

	/**
	 * 저장된 주문 내용을 수정하지 않고 유효한 승인 대기 미리보기만 한 번 승인합니다.
	 * 이 함수는 토스증권 주문 생성 API를 호출하지 않습니다.
	 *
	 * @param previewId 승인할 주문 미리보기 식별값
	 * @return 데이터베이스에 승인 시각이 기록된 주문 미리보기
	 * @throws OrderPreviewException 식별값 형식이 올바르지 않은 경우
	 * @throws OrderPreviewNotFoundException 저장된 미리보기를 찾을 수 없는 경우
	 * @throws OrderPreviewExpiredException 승인 유효시간이 지난 경우
	 * @throws OrderPreviewStateException 이미 승인되거나 사용된 경우
	 */
	public OrderPreviewResponse approvePreview(String previewId) {
		validatePreviewId(previewId);
		OffsetDateTime approvedAt = OffsetDateTime.now(clock);

		previewStore.expirePending(previewId, approvedAt);
		if (previewStore.approvePending(previewId, approvedAt)) {
			return findStoredPreview(previewId);
		}

		OrderPreviewResponse preview = findStoredPreview(previewId);
		switch (preview.status()) {
			case EXPIRED -> throw new OrderPreviewExpiredException(
					"주문 미리보기의 승인 시간이 지났습니다. 새 미리보기를 만들어 주세요.");
			case APPROVED -> throw new OrderPreviewStateException("이미 승인한 주문 미리보기입니다.");
			case CONSUMED -> throw new OrderPreviewStateException("이미 주문에 사용한 미리보기입니다.");
			case PENDING_APPROVAL -> throw new OrderPreviewStateException(
					"주문 미리보기 상태가 변경되어 승인하지 못했습니다. 다시 확인해 주세요.");
		}
		throw new IllegalStateException("처리할 수 없는 주문 미리보기 상태입니다.");
	}

	/**
	 * 승인 URL에 들어온 미리보기 식별값이 표준 UUID 문자열인지 확인합니다.
	 *
	 * @param previewId 검사할 미리보기 식별값
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
	 * 저장소에서 미리보기를 읽고 없으면 찾을 수 없음 오류를 발생시킵니다.
	 *
	 * @param previewId 조회할 미리보기 식별값
	 * @return 데이터베이스에 저장된 주문 미리보기
	 */
	private OrderPreviewResponse findStoredPreview(String previewId) {
		return previewStore.findById(previewId)
				.orElseThrow(() -> new OrderPreviewNotFoundException("주문 미리보기를 찾을 수 없습니다."));
	}

	/**
	 * 외부 조회 전에 계좌, 종목, 주문 방향, 유형과 양수 수량이 입력되었는지 검사합니다.
	 *
	 * @param request 검사할 주문 미리보기 요청
	 */
	private void validateBasicRequest(OrderPreviewRequest request) {
		if (request == null) {
			throw new OrderPreviewException("주문 미리보기 요청이 필요합니다.");
		}
		if (request.accountSeq() <= 0) {
			throw new OrderPreviewException("계좌 식별값은 1 이상이어야 합니다.");
		}
		if (request.symbol() == null || !request.symbol().matches("^[A-Za-z0-9.\\-]+$")) {
			throw new OrderPreviewException("종목 코드 형식이 올바르지 않습니다.");
		}
		if (request.side() == null) {
			throw new OrderPreviewException("주문 방향은 BUY 또는 SELL이어야 합니다.");
		}
		if (request.orderType() == null) {
			throw new OrderPreviewException("주문 유형은 LIMIT 또는 MARKET이어야 합니다.");
		}
		if (request.quantity() == null || request.quantity().signum() <= 0) {
			throw new OrderPreviewException("주문 수량은 0보다 커야 합니다.");
		}
		if (request.quantity().toPlainString().length() > 30) {
			throw new OrderPreviewException("주문 수량은 30자 이하여야 합니다.");
		}
	}

	/**
	 * 현재가의 통화를 국내 또는 미국 시장 정보로 변환하고 가격이 양수인지 확인합니다.
	 *
	 * @param stockPrice 토스증권에서 조회한 현재가
	 * @return 통화와 국가 코드가 연결된 시장 정보
	 */
	private Market resolveMarket(StockPriceResponse stockPrice) {
		if (stockPrice == null
				|| stockPrice.price() == null
				|| stockPrice.price().signum() <= 0
				|| stockPrice.currency() == null) {
			throw new OrderPreviewException("주문 계산에 사용할 현재가가 올바르지 않습니다.");
		}

		return switch (stockPrice.currency()) {
			case "KRW" -> new Market("KR", "KRW");
			case "USD" -> new Market("US", "USD");
			default -> throw new OrderPreviewException("지원하지 않는 거래 통화입니다.");
		};
	}

	/**
	 * 토스증권의 실제 수량 주문 규칙에 맞는 정수 또는 소수 수량인지 검사합니다.
	 *
	 * @param quantity 검사할 주문 수량
	 * @param side 매수 또는 매도 방향
	 * @param orderType 지정가 또는 시장가 유형
	 * @param market 현재가로 확인한 거래 시장
	 */
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
				&& side == OrderSide.SELL
				&& orderType == OrderType.MARKET;
		if (!fractionalAllowed) {
			throw new OrderPreviewException("소수점 수량은 미국 주식 시장가 매도에만 사용할 수 있습니다.");
		}
		if (scale > 6) {
			throw new OrderPreviewException("미국 주식 소수점 수량은 소수점 6자리까지 사용할 수 있습니다.");
		}
	}

	/**
	 * 지정가는 양수 가격이 필요하고 시장가는 가격을 받지 않는다는 규칙과 시장별 소수 자릿수를 검사합니다.
	 *
	 * @param price 사용자가 입력한 지정가 또는 null
	 * @param orderType 지정가 또는 시장가 유형
	 * @param market 현재가로 확인한 거래 시장
	 */
	private void validatePrice(BigDecimal price, OrderType orderType, Market market) {
		if (orderType == OrderType.MARKET) {
			if (price != null) {
				throw new OrderPreviewException("시장가 주문에는 가격을 입력할 수 없습니다.");
			}
			return;
		}

		if (price == null || price.signum() <= 0) {
			throw new OrderPreviewException("지정가 주문에는 0보다 큰 가격이 필요합니다.");
		}
		if (price.toPlainString().length() > 30) {
			throw new OrderPreviewException("주문 가격은 30자 이하여야 합니다.");
		}

		int scale = normalizedScale(price);
		if ("KR".equals(market.marketCountry()) && scale > 0) {
			throw new OrderPreviewException("국내 주식 지정가는 원 단위 정수여야 합니다.");
		}
		if ("US".equals(market.marketCountry())) {
			int maxScale = price.compareTo(BigDecimal.ONE) < 0 ? 4 : 2;
			if (scale > maxScale) {
				throw new OrderPreviewException(
						"미국 주식 지정가의 소수 자릿수가 토스증권 주문 규칙을 초과했습니다.");
			}
		}
	}

	/**
	 * 매도 주문 수량이 계좌에서 지금 새 주문에 사용할 수 있는 수량을 넘지 않는지 확인합니다.
	 *
	 * @param accountSeq 주문에 사용할 계좌 식별값
	 * @param symbol 주문할 종목 코드
	 * @param quantity 주문할 수량
	 */
	private void validateSellableQuantity(long accountSeq, String symbol, BigDecimal quantity) {
		SellableQuantityResponse response = sellableQuantityClient.getSellableQuantity(accountSeq, symbol);
		if (response.sellableQuantity().compareTo(quantity) < 0) {
			throw new OrderPreviewException("매도 가능 수량이 부족합니다.");
		}
	}

	/**
	 * 매수 예상 금액과 수수료의 합이 계좌의 현금 매수 가능 금액을 넘지 않는지 확인합니다.
	 *
	 * @param accountSeq 주문에 사용할 계좌 식별값
	 * @param currency 종목의 거래 통화
	 * @param requiredAmount 수수료를 포함한 예상 필요 금액
	 */
	private void validateBuyingPower(long accountSeq, String currency, BigDecimal requiredAmount) {
		BuyingPowerResponse response = buyingPowerClient.getBuyingPower(accountSeq, currency);
		if (response.cashBuyingPower().compareTo(requiredAmount) < 0) {
			throw new OrderPreviewException("매수 가능 금액이 부족합니다.");
		}
	}

	/**
	 * 계좌 수수료 목록에서 현재 종목 시장에 적용되는 안전한 수수료율을 찾습니다.
	 *
	 * @param accountSeq 주문에 사용할 계좌 식별값
	 * @param marketCountry 종목의 시장 국가 코드
	 * @return 0 이상 1 이하인 소수 비율 형태의 수수료율
	 */
	private BigDecimal findCommissionRate(long accountSeq, String marketCountry) {
		CommissionsResponse response = commissionsClient.getCommissions(accountSeq);
		List<CommissionItem> commissions = response.commissions();
		CommissionItem commission = commissions.stream()
				.filter(item -> marketCountry.equals(item.marketCountry()))
				.findFirst()
				.orElseThrow(() -> new OrderPreviewException("해당 시장의 매매 수수료를 찾지 못했습니다."));

		if (commission.commissionRate() == null
				|| commission.commissionRate().signum() < 0
				|| commission.commissionRate().compareTo(MAX_COMMISSION_RATE) > 0) {
			throw new OrderPreviewException("계산에 사용할 매매 수수료율이 올바르지 않습니다.");
		}
		return commission.commissionRate();
	}

	/**
	 * 국내 주문금액이 토스증권의 추가 확인 기준인 1억원 이상인지 확인합니다.
	 *
	 * @param currency 주문의 거래 통화
	 * @param orderAmount 수수료를 제외한 예상 주문금액
	 * @return 국내 1억원 이상 주문이면 true
	 */
	private boolean requiresHighValueConfirmation(String currency, BigDecimal orderAmount) {
		return "KRW".equals(currency) && orderAmount.compareTo(HIGH_VALUE_KRW_THRESHOLD) >= 0;
	}

	/**
	 * 값 끝의 불필요한 0을 제거한 뒤 실제 소수 자릿수를 계산합니다.
	 *
	 * @param value 소수 자릿수를 확인할 숫자
	 * @return 실제 소수 자릿수이며 정수이면 0
	 */
	private int normalizedScale(BigDecimal value) {
		return Math.max(value.stripTrailingZeros().scale(), 0);
	}

	/**
	 * 거래 시장의 국가 코드와 통화 코드를 함께 보관합니다.
	 *
	 * @param marketCountry 시장 국가 코드
	 * @param currency 거래 통화 코드
	 */
	private record Market(String marketCountry, String currency) {
	}
}
