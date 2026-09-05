package com.jusika.backend.toss.orderhistory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.jusika.backend.orderhistory.OrderDetailResponse;
import com.jusika.backend.orderhistory.OrderDetailResponse.ExecutionDetail;
import com.jusika.backend.orderhistory.OrderHistoryNotFoundException;
import com.jusika.backend.orderhistory.OrderHistoryRequestException;
import com.jusika.backend.orderhistory.OrderHistoryServiceException;
import com.jusika.backend.orderhistory.OrderStatus;
import com.jusika.backend.orderpreview.OrderSide;
import com.jusika.backend.toss.auth.TossAccessTokenProvider;
import com.jusika.backend.toss.orderhistory.TossOrderHistoryApiResponse.TossExecution;
import com.jusika.backend.toss.orderhistory.TossOrderHistoryApiResponse.TossOrderResult;

/**
 * 토스증권 주문 상세 API를 읽기 전용으로 호출하고 체결 숫자와 시각을 안전하게 변환합니다.
 */
@Component
public class TossOrderHistoryClient {

	private static final String ACCOUNT_HEADER = "X-Tossinvest-Account";
	private static final int MAX_ORDER_ID_LENGTH = 512;
	private static final int MAX_CODE_LENGTH = 64;
	private static final int MAX_DECIMAL_LENGTH = 30;
	private static final Pattern SYMBOL_PATTERN = Pattern.compile("^[A-Za-z0-9.\\-]+$");
	private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Za-z0-9_-]+$");
	private static final Pattern DECIMAL_PATTERN = Pattern.compile("^\\d+(\\.\\d+)?$");
	private static final Set<String> SUPPORTED_CURRENCIES = Set.of("KRW", "USD");

	private final RestClient restClient;
	private final TossAccessTokenProvider tokenProvider;

	/**
	 * 토스증권 전용 REST 클라이언트와 유효한 토큰 공급자를 전달받습니다.
	 *
	 * @param tossRestClient 토스증권 API 전용 REST 클라이언트
	 * @param tokenProvider 유효한 액세스 토큰을 제공하는 객체
	 */
	public TossOrderHistoryClient(
			RestClient tossRestClient,
			TossAccessTokenProvider tokenProvider) {
		this.restClient = tossRestClient;
		this.tokenProvider = tokenProvider;
	}

	/**
	 * 지정한 계좌와 토스증권 주문 식별값으로 현재 상태와 누적 체결 결과를 조회합니다.
	 * 이 함수는 읽기 전용 GET 요청만 사용하므로 주문을 생성·정정·취소하지 않습니다.
	 *
	 * @param accountSeq 계좌 목록 API에서 받은 계좌 식별값
	 * @param orderId 토스증권이 주문 생성 때 반환한 주문 식별값
	 * @return 숫자와 시각 변환을 마친 주문 상세
	 */
	public OrderDetailResponse getOrder(long accountSeq, String orderId) {
		validateAccountSeq(accountSeq);
		String validatedOrderId = validateOrderId(orderId);

		try {
			TossOrderHistoryApiResponse response = restClient.get()
					.uri(uriBuilder -> uriBuilder
							.path("/api/v1/orders")
							.pathSegment(validatedOrderId)
							.build())
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenProvider.getAccessToken())
					.header(ACCOUNT_HEADER, Long.toString(accountSeq))
					.retrieve()
					.body(TossOrderHistoryApiResponse.class);
			return convertResponse(response, accountSeq, validatedOrderId);
		} catch (RestClientResponseException exception) {
			if (exception.getStatusCode().value() == 404) {
				throw new OrderHistoryNotFoundException("지정한 계좌에서 주문을 찾을 수 없습니다.");
			}
			throw new OrderHistoryServiceException(
					"토스증권 주문 상세 조회에 실패했습니다. HTTP 상태: "
							+ exception.getStatusCode().value());
		} catch (OrderHistoryRequestException
				| OrderHistoryNotFoundException
				| OrderHistoryServiceException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			throw new OrderHistoryServiceException("토스증권 주문 이력 서버와 통신하지 못했습니다.");
		}
	}

	/**
	 * 계좌 식별값이 토스증권 계좌 헤더로 사용할 수 있는 양수인지 확인합니다.
	 *
	 * @param accountSeq 검사할 계좌 식별값
	 */
	private void validateAccountSeq(long accountSeq) {
		if (accountSeq <= 0) {
			throw new OrderHistoryRequestException("계좌 식별값은 1 이상이어야 합니다.");
		}
	}

	/**
	 * 불투명 주문 식별값의 길이와 HTTP 경로에 사용할 수 없는 제어문자를 확인합니다.
	 *
	 * @param orderId 검사할 토스증권 주문 식별값
	 * @return 앞뒤 공백을 제거하지 않은 검증된 원본 식별값
	 */
	private String validateOrderId(String orderId) {
		if (orderId == null
				|| orderId.isBlank()
				|| orderId.length() > MAX_ORDER_ID_LENGTH
				|| orderId.codePoints().anyMatch(codePoint ->
						Character.isWhitespace(codePoint) || codePoint < 32 || codePoint == 127)) {
			throw new OrderHistoryRequestException("주문 식별값 형식이 올바르지 않습니다.");
		}
		return orderId;
	}

	/**
	 * 토스증권 원본 응답의 필수값과 값 사이 관계를 검증해 우리 서버 응답으로 변환합니다.
	 *
	 * @param response 토스증권 주문 상세 원본 응답
	 * @param accountSeq 조회에 사용한 계좌 식별값
	 * @param requestedOrderId 요청에 사용한 주문 식별값
	 * @return 검증과 자료형 변환을 마친 주문 상세
	 */
	private OrderDetailResponse convertResponse(
			TossOrderHistoryApiResponse response,
			long accountSeq,
			String requestedOrderId) {
		TossOrderResult result = response == null ? null : response.result();
		if (result == null || !requestedOrderId.equals(result.orderId())) {
			throw malformedResponse();
		}

		String symbol = validateSymbol(result.symbol());
		OrderSide side = parseSide(result.side());
		String orderTypeCode = validateCode(result.orderType());
		String timeInForceCode = validateCode(result.timeInForce());
		String brokerStatusCode = validateCode(result.status());
		BigDecimal price = parseOptionalDecimal(result.price(), true);
		BigDecimal quantity = parseRequiredDecimal(result.quantity(), true);
		BigDecimal orderAmount = parseOptionalDecimal(result.orderAmount(), true);
		String currency = validateCurrency(result.currency());
		OffsetDateTime orderedAt = parseRequiredDateTime(result.orderedAt());
		OffsetDateTime canceledAt = parseOptionalDateTime(result.canceledAt());
		ExecutionDetail execution = convertExecution(result.execution(), quantity);

		return new OrderDetailResponse(
				accountSeq,
				result.orderId(),
				symbol,
				side,
				orderTypeCode,
				timeInForceCode,
				OrderStatus.fromBrokerCode(brokerStatusCode),
				brokerStatusCode,
				price,
				quantity,
				orderAmount,
				currency,
				orderedAt,
				canceledAt,
				execution);
	}

	/**
	 * 누적 체결 결과의 숫자·시각·결제일을 변환하고 주문 수량을 넘지 않는지 확인합니다.
	 *
	 * @param execution 토스증권 누적 체결 원본 응답
	 * @param orderQuantity 전체 주문 수량
	 * @return 검증과 변환을 마친 누적 체결 결과
	 */
	private ExecutionDetail convertExecution(
			TossExecution execution,
			BigDecimal orderQuantity) {
		if (execution == null) {
			throw malformedResponse();
		}
		BigDecimal filledQuantity = parseRequiredDecimal(execution.filledQuantity(), false);
		if (filledQuantity.compareTo(orderQuantity) > 0) {
			throw malformedResponse();
		}
		return new ExecutionDetail(
				filledQuantity,
				parseOptionalDecimal(execution.averageFilledPrice(), true),
				parseOptionalDecimal(execution.filledAmount(), false),
				parseOptionalDecimal(execution.commission(), false),
				parseOptionalDecimal(execution.tax(), false),
				parseOptionalDateTime(execution.filledAt()),
				parseOptionalDate(execution.settlementDate()));
	}

	/**
	 * 종목 코드의 허용 문자를 확인하고 영문자를 대문자로 정규화합니다.
	 *
	 * @param symbol 검사할 국내 또는 미국 주식 종목 코드
	 * @return 대문자로 정규화한 종목 코드
	 */
	private String validateSymbol(String symbol) {
		if (symbol == null || !SYMBOL_PATTERN.matcher(symbol).matches()) {
			throw malformedResponse();
		}
		return symbol.toUpperCase(Locale.ROOT);
	}

	/**
	 * 주문 방향 문자열을 매수 또는 매도 열거형으로 변환합니다.
	 *
	 * @param side 토스증권 원본 주문 방향
	 * @return 매수 또는 매도 방향
	 */
	private OrderSide parseSide(String side) {
		try {
			return OrderSide.valueOf(side);
		} catch (NullPointerException | IllegalArgumentException exception) {
			throw malformedResponse();
		}
	}

	/**
	 * 향후 새 코드가 추가되어도 원문을 보존할 수 있도록 비어 있지 않은 코드인지 확인합니다.
	 *
	 * @param code 검사할 토스증권 원본 코드
	 * @return 검증된 원본 코드
	 */
	private String validateCode(String code) {
		if (code == null
				|| code.isBlank()
				|| code.length() > MAX_CODE_LENGTH
				|| !CODE_PATTERN.matcher(code).matches()) {
			throw malformedResponse();
		}
		return code;
	}

	/**
	 * 통화 코드가 현재 지원하는 원화 또는 달러인지 확인합니다.
	 *
	 * @param currency 토스증권 원본 통화 코드
	 * @return 검증된 통화 코드
	 */
	private String validateCurrency(String currency) {
		if (!SUPPORTED_CURRENCIES.contains(currency)) {
			throw malformedResponse();
		}
		return currency;
	}

	/**
	 * 필수 문자열 숫자를 BigDecimal로 변환하고 양수 또는 0 이상 조건을 확인합니다.
	 *
	 * @param value 변환할 숫자 문자열
	 * @param positiveRequired 0보다 커야 하면 true, 0을 허용하면 false
	 * @return 정확한 십진수 값
	 */
	private BigDecimal parseRequiredDecimal(String value, boolean positiveRequired) {
		BigDecimal decimal = parseDecimal(value);
		if (positiveRequired ? decimal.signum() <= 0 : decimal.signum() < 0) {
			throw malformedResponse();
		}
		return decimal;
	}

	/**
	 * 선택 문자열 숫자가 있으면 BigDecimal로 변환하고 없으면 null을 유지합니다.
	 *
	 * @param value 변환할 선택 숫자 문자열
	 * @param positiveRequired 값이 있을 때 0보다 커야 하면 true, 0을 허용하면 false
	 * @return 변환된 십진수이며 원본이 null이면 null
	 */
	private BigDecimal parseOptionalDecimal(String value, boolean positiveRequired) {
		return value == null ? null : parseRequiredDecimal(value, positiveRequired);
	}

	/**
	 * 숫자 문자열 길이를 제한하고 정확한 BigDecimal로 변환합니다.
	 *
	 * @param value 변환할 필수 숫자 문자열
	 * @return 변환된 십진수
	 */
	private BigDecimal parseDecimal(String value) {
		try {
			if (value == null
					|| value.isBlank()
					|| value.length() > MAX_DECIMAL_LENGTH
					|| !DECIMAL_PATTERN.matcher(value).matches()) {
				throw malformedResponse();
			}
			return new BigDecimal(value);
		} catch (NumberFormatException exception) {
			throw malformedResponse();
		}
	}

	/**
	 * 필수 ISO 8601 주문 시각을 OffsetDateTime으로 변환합니다.
	 *
	 * @param value 변환할 필수 시각 문자열
	 * @return 변환된 오프셋 포함 시각
	 */
	private OffsetDateTime parseRequiredDateTime(String value) {
		OffsetDateTime parsed = parseOptionalDateTime(value);
		if (parsed == null) {
			throw malformedResponse();
		}
		return parsed;
	}

	/**
	 * 선택 ISO 8601 시각이 있으면 OffsetDateTime으로 변환하고 없으면 null을 유지합니다.
	 *
	 * @param value 변환할 선택 시각 문자열
	 * @return 변환된 오프셋 포함 시각이며 원본이 null이면 null
	 */
	private OffsetDateTime parseOptionalDateTime(String value) {
		try {
			return value == null ? null : OffsetDateTime.parse(value);
		} catch (DateTimeParseException exception) {
			throw malformedResponse();
		}
	}

	/**
	 * 선택 ISO 날짜가 있으면 LocalDate로 변환하고 없으면 null을 유지합니다.
	 *
	 * @param value 변환할 선택 날짜 문자열
	 * @return 변환된 날짜이며 원본이 null이면 null
	 */
	private LocalDate parseOptionalDate(String value) {
		try {
			return value == null ? null : LocalDate.parse(value);
		} catch (DateTimeParseException exception) {
			throw malformedResponse();
		}
	}

	/**
	 * 실제 주문 값은 포함하지 않는 동일한 응답 형식 오류를 만듭니다.
	 *
	 * @return 안전한 토스증권 주문 상세 응답 오류
	 */
	private OrderHistoryServiceException malformedResponse() {
		return new OrderHistoryServiceException("토스증권 주문 상세 응답 형식이 올바르지 않습니다.");
	}
}
