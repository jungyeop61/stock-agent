package com.jusika.backend.toss.order;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.jusika.backend.order.AmountOrderSubmissionRequest;
import com.jusika.backend.order.OrderCreationResponse;
import com.jusika.backend.order.OrderModificationSubmissionRequest;
import com.jusika.backend.order.OrderOperationResponse;
import com.jusika.backend.order.OrderTimeInForce;
import com.jusika.backend.order.QuantityOrderSubmissionRequest;
import com.jusika.backend.orderpreview.OrderSide;
import com.jusika.backend.orderpreview.OrderType;
import com.jusika.backend.toss.auth.TossAccessTokenProvider;
import com.jusika.backend.toss.order.TossOrderApiRequests.AmountRequest;
import com.jusika.backend.toss.order.TossOrderApiRequests.QuantityRequest;
import com.jusika.backend.toss.order.TossOrderApiRequests.ModificationRequest;
import com.jusika.backend.toss.order.TossOrderApiResponse.TossOrderResult;
import com.jusika.backend.toss.order.TossOrderOperationApiResponse.TossOrderOperationResult;

/**
 * 토스증권 주문 생성·취소 API의 요청 형식과 응답을 담당합니다.
 * 이 객체를 호출하면 실제 주문이 생성되거나 취소되므로 승인·재검증 서비스에서만 사용해야 합니다.
 */
@Component
public class TossOrderClient {

	private static final String ACCOUNT_HEADER = "X-Tossinvest-Account";
	private static final Pattern CLIENT_ORDER_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]+$");
	private static final Pattern SYMBOL_PATTERN = Pattern.compile("^[A-Za-z0-9.-]+$");
	private static final Pattern US_SYMBOL_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9.-]*$");

	private final RestClient restClient;
	private final TossAccessTokenProvider tokenProvider;

	/**
	 * 토스증권 전용 REST 클라이언트와 유효한 토큰 공급자를 전달받습니다.
	 *
	 * @param tossRestClient 토스증권 API 전용 REST 클라이언트
	 * @param tokenProvider 유효한 액세스 토큰을 제공하는 객체
	 */
	public TossOrderClient(RestClient tossRestClient, TossAccessTokenProvider tokenProvider) {
		this.restClient = tossRestClient;
		this.tokenProvider = tokenProvider;
	}

	/**
	 * 국내·미국 주식의 수량 기반 지정가 또는 시장가 주문을 토스증권에 전송합니다.
	 *
	 * @param accountSeq 계좌 목록 API에서 받은 계좌 식별값
	 * @param request 전송할 수량 기반 주문 내용
	 * @return 토스증권 주문 식별값과 요청 멱등성 식별값
	 * @throws TossOrderException 요청 형식, 인증, 서버 통신 또는 응답이 올바르지 않은 경우
	 */
	public OrderCreationResponse createQuantityOrder(
			long accountSeq,
			QuantityOrderSubmissionRequest request) {
		validateAccountSeq(accountSeq);
		if (request == null) {
			throw new TossOrderException("수량 기반 주문 요청이 필요합니다.");
		}

		String clientOrderId = validateClientOrderId(request.clientOrderId());
		String symbol = normalizeSymbol(request.symbol(), false);
		validateSide(request.side());
		validateOrderType(request.orderType());
		OrderTimeInForce timeInForce = normalizeTimeInForce(
				request.timeInForce(), request.orderType());
		String quantity = validateQuantity(
				request.quantity(), request.side(), request.orderType());
		String price = validatePrice(request.price(), request.orderType());

		QuantityRequest apiRequest = new QuantityRequest(
				clientOrderId,
				symbol,
				request.side(),
				request.orderType(),
				timeInForce,
				quantity,
				price,
				request.confirmHighValueOrder());
		return submitOrder(accountSeq, apiRequest, clientOrderId);
	}

	/**
	 * 미국 주식의 달러 금액 기반 시장가 주문을 토스증권에 전송합니다.
	 *
	 * @param accountSeq 계좌 목록 API에서 받은 계좌 식별값
	 * @param request 전송할 미국 주식 금액 기반 주문 내용
	 * @return 토스증권 주문 식별값과 요청 멱등성 식별값
	 * @throws TossOrderException 요청 형식, 인증, 서버 통신 또는 응답이 올바르지 않은 경우
	 */
	public OrderCreationResponse createAmountOrder(
			long accountSeq,
			AmountOrderSubmissionRequest request) {
		validateAccountSeq(accountSeq);
		if (request == null) {
			throw new TossOrderException("금액 기반 주문 요청이 필요합니다.");
		}

		String clientOrderId = validateClientOrderId(request.clientOrderId());
		String symbol = normalizeSymbol(request.symbol(), true);
		validateSide(request.side());
		String orderAmount = validatePositiveDecimal(request.orderAmount(), "주문 금액");

		AmountRequest apiRequest = new AmountRequest(
				clientOrderId,
				symbol,
				request.side(),
				OrderType.MARKET,
				orderAmount,
				request.confirmHighValueOrder());
		return submitOrder(accountSeq, apiRequest, clientOrderId);
	}

	/**
	 * 토스증권 주문 식별값으로 아직 체결되지 않은 주문의 취소를 요청합니다.
	 * 현재 안전 취소 서비스에는 연결하지 않았으므로 이 메서드는 자동 실행되지 않습니다.
	 *
	 * @param accountSeq 취소할 주문의 계좌 식별값
	 * @param orderId 취소할 토스증권 주문 식별값
	 * @return 취소 접수로 새로 발급된 토스증권 주문 식별값
	 * @throws TossOrderException 요청 형식, 인증, 통신 또는 응답이 올바르지 않은 경우
	 */
	public OrderOperationResponse cancelOrder(long accountSeq, String orderId) {
		validateAccountSeq(accountSeq);
		String validatedOrderId = validateOrderId(orderId);
		String accessToken = getAccessTokenBeforeSubmission();
		try {
			TossOrderOperationApiResponse response = restClient.post()
					.uri("/api/v1/orders/{orderId}/cancel", validatedOrderId)
					.contentType(MediaType.APPLICATION_JSON)
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
					.header(ACCOUNT_HEADER, Long.toString(accountSeq))
					.body(new Object())
					.retrieve()
					.body(TossOrderOperationApiResponse.class);
			return convertOperationResponse(response, validatedOrderId, "취소");
		} catch (RestClientResponseException exception) {
			int status = exception.getStatusCode().value();
			throw new TossOrderException(
					"토스증권 주문 취소에 실패했습니다. HTTP 상태: " + status,
					status,
					status >= 500);
		} catch (TossOrderException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			throw new TossOrderException("토스증권 주문 취소 결과를 확인하지 못했습니다.", null, true);
		}
	}

	/**
	 * 국내 주식의 유형·수량·가격 또는 미국 주식의 유형·가격을 정정합니다.
	 * 현재 정정 서비스에는 연결하지 않았으므로 이 메서드는 자동 실행되지 않습니다.
	 *
	 * @param accountSeq 정정할 주문의 계좌 식별값
	 * @param orderId 정정할 토스증권 원주문 식별값
	 * @param request 최종 검증을 마친 정정 내용
	 * @return 정정 접수로 새로 발급된 토스증권 주문 식별값
	 */
	public OrderOperationResponse modifyOrder(
			long accountSeq, String orderId, OrderModificationSubmissionRequest request) {
		validateAccountSeq(accountSeq);
		String validatedOrderId = validateOrderId(orderId);
		if (request == null) throw new TossOrderException("주문 정정 요청이 필요합니다.");
		validateOrderType(request.orderType());

		String quantity;
		if ("KRW".equals(request.currency())) {
			quantity = validatePositiveDecimal(request.quantity(), "정정 수량");
			if (normalizedScale(request.quantity()) > 0) {
				throw new TossOrderException("국내 주식 정정 수량은 정수여야 합니다.");
			}
		} else if ("USD".equals(request.currency())) {
			if (request.quantity() != null) {
				throw new TossOrderException("미국 주식 주문 정정은 수량을 보낼 수 없습니다.");
			}
			quantity = null;
		} else {
			throw new TossOrderException("지원하지 않는 정정 주문 통화입니다.");
		}
		String price = validatePrice(request.price(), request.orderType());
		if (price != null) validateModificationPriceScale(request.price(), request.currency());

		ModificationRequest apiRequest = new ModificationRequest(
				request.orderType(), quantity, price, request.confirmHighValueOrder());
		return submitOrderOperation(
				accountSeq, validatedOrderId, "/api/v1/orders/{orderId}/modify", apiRequest,
				"정정");
	}

	/** 인증과 계좌 헤더를 넣어 정정 요청을 보내고 새 주문번호를 검증합니다. */
	private OrderOperationResponse submitOrderOperation(
			long accountSeq, String originalOrderId, String path, Object body, String operationName) {
		String accessToken = getAccessTokenBeforeSubmission();
		try {
			TossOrderOperationApiResponse response = restClient.post()
					.uri(path, originalOrderId)
					.contentType(MediaType.APPLICATION_JSON)
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
					.header(ACCOUNT_HEADER, Long.toString(accountSeq))
					.body(body)
					.retrieve()
					.body(TossOrderOperationApiResponse.class);
			return convertOperationResponse(response, originalOrderId, operationName);
		} catch (RestClientResponseException exception) {
			int status = exception.getStatusCode().value();
			throw new TossOrderException(
					"토스증권 주문 " + operationName + "에 실패했습니다. HTTP 상태: " + status,
					status, status >= 500);
		} catch (TossOrderException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			throw new TossOrderException(
					"토스증권 주문 " + operationName + " 결과를 확인하지 못했습니다.", null, true);
		}
	}

	/** 정정·취소 성공 응답에 원주문과 다른 새 주문번호가 있는지 검사합니다. */
	private OrderOperationResponse convertOperationResponse(
			TossOrderOperationApiResponse response, String requestedOrderId, String operationName) {
		TossOrderOperationResult result = response == null ? null : response.result();
		if (result == null || result.orderId() == null || result.orderId().isBlank()
				|| requestedOrderId.equals(result.orderId())) {
			throw new TossOrderException(
					"토스증권 주문 " + operationName + " 응답 형식이 올바르지 않습니다.", null, true);
		}
		return new OrderOperationResponse(result.orderId());
	}

	/**
	 * 인증 토큰과 계좌 헤더를 넣어 주문을 전송하고 원본 응답을 안전하게 변환합니다.
	 *
	 * @param accountSeq 주문에 사용할 계좌 식별값
	 * @param apiRequest 토스증권 JSON 형식으로 변환한 주문 요청
	 * @param clientOrderId 요청과 응답이 같은지 확인할 멱등성 식별값
	 * @return 검증을 마친 주문 생성 결과
	 */
	private OrderCreationResponse submitOrder(
			long accountSeq,
			Object apiRequest,
			String clientOrderId) {
		String accessToken = getAccessTokenBeforeSubmission();
		try {
			TossOrderApiResponse response = restClient.post()
					.uri("/api/v1/orders")
					.contentType(MediaType.APPLICATION_JSON)
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
					.header(ACCOUNT_HEADER, Long.toString(accountSeq))
					.body(apiRequest)
					.retrieve()
					.body(TossOrderApiResponse.class);
			return convertResponse(response, clientOrderId);
		} catch (RestClientResponseException exception) {
			int status = exception.getStatusCode().value();
			throw new TossOrderException(
					"토스증권 주문 생성에 실패했습니다. HTTP 상태: " + status,
					status,
					status >= 500);
		} catch (TossOrderException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			throw new TossOrderException(
					"토스증권 주문 생성 결과를 확인하지 못했습니다.",
					null,
					true);
		}
	}

	/**
	 * 실제 주문 전송을 시작하기 전에 인증 토큰을 얻어 인증 실패와 전송 결과 불명을 구분합니다.
	 *
	 * @return 토스증권 요청에 사용할 액세스 토큰
	 */
	private String getAccessTokenBeforeSubmission() {
		try {
			String accessToken = tokenProvider.getAccessToken();
			if (accessToken == null || accessToken.isBlank()) {
				throw new TossOrderException("토스증권 주문 전에 유효한 인증 토큰을 준비하지 못했습니다.");
			}
			return accessToken;
		} catch (TossOrderException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			throw new TossOrderException("토스증권 주문 전에 인증 토큰을 준비하지 못했습니다.");
		}
	}

	/**
	 * 토스증권이 반환한 주문 식별값과 요청 멱등성 식별값을 검증합니다.
	 * 성공 응답이 불완전하면 주문 접수 여부를 알 수 없는 상태로 처리합니다.
	 *
	 * @param response 토스증권 주문 생성 원본 응답
	 * @param requestedClientOrderId 요청에 넣은 멱등성 식별값
	 * @return 검증된 주문 생성 결과
	 */
	private OrderCreationResponse convertResponse(
			TossOrderApiResponse response,
			String requestedClientOrderId) {
		TossOrderResult result = response == null ? null : response.result();
		if (result == null || result.orderId() == null || result.orderId().isBlank()) {
			throw new TossOrderException(
					"토스증권 주문 생성 응답 형식이 올바르지 않습니다.",
					null,
					true);
		}
		if (!requestedClientOrderId.equals(result.clientOrderId())) {
			throw new TossOrderException(
					"토스증권이 요청과 다른 주문 멱등성 식별값을 반환했습니다.",
					null,
					true);
		}
		return new OrderCreationResponse(result.orderId(), result.clientOrderId());
	}

	/**
	 * 계좌 식별값이 토스증권 계좌 헤더로 사용할 수 있는 양수인지 확인합니다.
	 *
	 * @param accountSeq 검사할 계좌 식별값
	 */
	private void validateAccountSeq(long accountSeq) {
		if (accountSeq <= 0) {
			throw new TossOrderException("계좌 식별값은 1 이상이어야 합니다.");
		}
	}

	/** 주문 식별값의 길이와 공백·제어문자 포함 여부를 검사합니다. */
	private String validateOrderId(String orderId) {
		if (orderId == null || orderId.isBlank() || orderId.length() > 512
				|| orderId.chars().anyMatch(Character::isWhitespace)
				|| orderId.chars().anyMatch(Character::isISOControl)) {
			throw new TossOrderException("주문 식별값 형식이 올바르지 않습니다.");
		}
		return orderId;
	}

	/**
	 * 우리 서버가 만든 주문 멱등성 식별값의 길이와 허용 문자를 확인합니다.
	 * 주문 안전을 위해 공식 명세의 선택 필드를 이 클라이언트에서는 필수로 사용합니다.
	 *
	 * @param clientOrderId 검사할 멱등성 식별값
	 * @return 검증된 멱등성 식별값
	 */
	private String validateClientOrderId(String clientOrderId) {
		if (clientOrderId == null
				|| clientOrderId.isBlank()
				|| clientOrderId.length() > 36
				|| !CLIENT_ORDER_ID_PATTERN.matcher(clientOrderId).matches()) {
			throw new TossOrderException(
					"주문 멱등성 식별값은 36자 이하의 영문, 숫자, 하이픈과 밑줄만 사용할 수 있습니다.");
		}
		return clientOrderId;
	}

	/**
	 * 종목 코드의 허용 문자와 미국 주식 전용 조건을 확인하고 대문자로 정리합니다.
	 *
	 * @param symbol 검사할 종목 코드
	 * @param usOnly 미국 주식 코드만 허용해야 하면 true
	 * @return 대문자로 정규화한 종목 코드
	 */
	private String normalizeSymbol(String symbol, boolean usOnly) {
		Pattern pattern = usOnly ? US_SYMBOL_PATTERN : SYMBOL_PATTERN;
		if (symbol == null || symbol.isBlank() || !pattern.matcher(symbol).matches()) {
			throw new TossOrderException(usOnly
					? "금액 주문에는 올바른 미국 주식 종목 코드가 필요합니다."
					: "종목 코드 형식이 올바르지 않습니다.");
		}
		return symbol.toUpperCase(Locale.ROOT);
	}

	/**
	 * 주문 방향이 매수 또는 매도 중 하나인지 확인합니다.
	 *
	 * @param side 검사할 주문 방향
	 */
	private void validateSide(OrderSide side) {
		if (side == null) {
			throw new TossOrderException("주문 방향은 BUY 또는 SELL이어야 합니다.");
		}
	}

	/**
	 * 수량 주문 유형이 지정가 또는 시장가 중 하나인지 확인합니다.
	 *
	 * @param orderType 검사할 주문 유형
	 */
	private void validateOrderType(OrderType orderType) {
		if (orderType == null) {
			throw new TossOrderException("주문 유형은 LIMIT 또는 MARKET이어야 합니다.");
		}
	}

	/**
	 * 주문 유효 조건의 기본값을 당일로 적용하고 장 마감 시장가 조합을 차단합니다.
	 * 시장별 세부 허용 여부는 승인 후 최종 재검증 단계에서 확인합니다.
	 *
	 * @param timeInForce 사용자가 지정한 주문 유효 조건
	 * @param orderType 지정가 또는 시장가 유형
	 * @return null을 당일 조건으로 바꾼 주문 유효 조건
	 */
	private OrderTimeInForce normalizeTimeInForce(
			OrderTimeInForce timeInForce,
			OrderType orderType) {
		OrderTimeInForce normalized = timeInForce == null ? OrderTimeInForce.DAY : timeInForce;
		if (normalized == OrderTimeInForce.CLS && orderType != OrderType.LIMIT) {
			throw new TossOrderException("장 마감 주문은 지정가 주문에서만 사용할 수 있습니다.");
		}
		return normalized;
	}

	/**
	 * 수량을 양의 문자열 소수로 변환하고 소수 수량 주문 조합과 자릿수를 확인합니다.
	 *
	 * @param quantity 검사할 주문 수량
	 * @param side 매수 또는 매도 방향
	 * @param orderType 지정가 또는 시장가 유형
	 * @return 토스증권에 전달할 문자열 형태 수량
	 */
	private String validateQuantity(
			BigDecimal quantity,
			OrderSide side,
			OrderType orderType) {
		String normalized = validatePositiveDecimal(quantity, "주문 수량");
		int scale = normalizedScale(quantity);
		if (scale > 0 && (side != OrderSide.SELL || orderType != OrderType.MARKET)) {
			throw new TossOrderException("소수점 수량은 미국 주식 시장가 매도에만 사용할 수 있습니다.");
		}
		if (scale > 6) {
			throw new TossOrderException("미국 주식 소수점 수량은 소수점 6자리까지 사용할 수 있습니다.");
		}
		return normalized;
	}

	/**
	 * 지정가는 양수 가격이 필요하고 시장가는 가격을 받지 않는다는 규칙을 확인합니다.
	 *
	 * @param price 검사할 지정가 또는 null
	 * @param orderType 지정가 또는 시장가 유형
	 * @return 토스증권에 전달할 문자열 형태 지정가이며 시장가이면 null
	 */
	private String validatePrice(BigDecimal price, OrderType orderType) {
		if (orderType == OrderType.MARKET) {
			if (price != null) {
				throw new TossOrderException("시장가 주문에는 가격을 입력할 수 없습니다.");
			}
			return null;
		}
		if (price == null) {
			throw new TossOrderException("지정가 주문에는 0보다 큰 가격이 필요합니다.");
		}
		return validatePositiveDecimal(price, "주문 가격");
	}

	/**
	 * 금융 숫자가 양수이고 토스증권 최대 문자열 길이인 30자를 넘지 않는지 확인합니다.
	 *
	 * @param value 검사할 정확한 소수 값
	 * @param fieldName 오류 메시지에 사용할 필드 이름
	 * @return 지수 표기법을 사용하지 않은 문자열 숫자
	 */
	private String validatePositiveDecimal(BigDecimal value, String fieldName) {
		if (value == null || value.signum() <= 0) {
			throw new TossOrderException(fieldName + "은 0보다 커야 합니다.");
		}
		String plainValue = value.toPlainString();
		if (plainValue.length() > 30) {
			throw new TossOrderException(fieldName + "은 30자 이하여야 합니다.");
		}
		return plainValue;
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

	/** 국내 원 단위와 미국 달러 가격 소수 자릿수 규칙을 검사합니다. */
	private void validateModificationPriceScale(BigDecimal price, String currency) {
		int scale = normalizedScale(price);
		if ("KRW".equals(currency) && scale > 0) {
			throw new TossOrderException("국내 주식 정정 지정가는 원 단위 정수여야 합니다.");
		}
		if ("USD".equals(currency)) {
			int maxScale = price.compareTo(BigDecimal.ONE) < 0 ? 4 : 2;
			if (scale > maxScale) {
				throw new TossOrderException("미국 주식 정정 지정가의 소수 자릿수가 너무 많습니다.");
			}
		}
	}
}
