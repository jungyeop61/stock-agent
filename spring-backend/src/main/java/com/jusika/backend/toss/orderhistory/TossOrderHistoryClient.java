package com.jusika.backend.toss.orderhistory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
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
import com.jusika.backend.orderhistory.OrderListResponse;
import com.jusika.backend.orderhistory.OrderListStatus;
import com.jusika.backend.orderhistory.OrderStatus;
import com.jusika.backend.orderpreview.OrderSide;
import com.jusika.backend.toss.auth.TossAccessTokenProvider;
import com.jusika.backend.toss.orderhistory.TossOrderListApiResponse.TossOrderPage;
import com.jusika.backend.toss.orderhistory.TossOrderHistoryApiResponse.TossExecution;
import com.jusika.backend.toss.orderhistory.TossOrderHistoryApiResponse.TossOrderResult;

/**
 * 토스증권 주문 목록과 상세 API를 읽기 전용으로 호출하고 숫자와 시각을 안전하게 변환합니다.
 */
@Component
public class TossOrderHistoryClient {

	private static final String ACCOUNT_HEADER = "X-Tossinvest-Account";
	private static final int MAX_ORDER_ID_LENGTH = 512;
	private static final int MAX_CODE_LENGTH = 64;
	private static final int MAX_CURSOR_LENGTH = 2048;
	private static final int MAX_DECIMAL_LENGTH = 30;
	private static final int MAX_SYMBOL_LENGTH = 32;
	private static final int DEFAULT_CLOSED_LIMIT = 20;
	private static final int MAX_CLOSED_LIMIT = 100;
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
	 * 지정한 계좌의 진행 중 또는 종료된 주문을 필터와 페이지 조건에 맞게 조회합니다.
	 * 이 함수는 읽기 전용 GET 요청만 사용하므로 주문을 생성·정정·취소하지 않습니다.
	 *
	 * @param accountSeq 계좌 목록 API에서 받은 계좌 식별값
	 * @param status 진행 중 또는 종료된 주문 그룹
	 * @param symbol 선택 종목 코드
	 * @param from 선택 조회 시작일
	 * @param to 선택 조회 종료일
	 * @param cursor 종료 주문의 선택 다음 페이지 커서
	 * @param limit 종료 주문의 선택 페이지 크기
	 * @return 검증과 자료형 변환을 마친 주문 목록
	 */
	public OrderListResponse getOrders(
			long accountSeq,
			OrderListStatus status,
			String symbol,
			LocalDate from,
			LocalDate to,
			String cursor,
			Integer limit) {
		validateAccountSeq(accountSeq);
		OrderListStatus validatedStatus = validateListStatus(status);
		String validatedSymbol = validateOptionalRequestSymbol(symbol);
		validateDateRange(from, to);
		String validatedCursor = validateCursor(validatedStatus, cursor);
		Integer validatedLimit = validateLimit(validatedStatus, limit);

		try {
			TossOrderListApiResponse response = restClient.get()
					.uri(uriBuilder -> {
						uriBuilder.path("/api/v1/orders")
								.queryParam("status", validatedStatus.name());
						if (validatedSymbol != null) {
							uriBuilder.queryParam("symbol", validatedSymbol);
						}
						if (from != null) {
							uriBuilder.queryParam("from", from);
						}
						if (to != null) {
							uriBuilder.queryParam("to", to);
						}
						if (validatedCursor != null) {
							uriBuilder.queryParam("cursor", validatedCursor);
						}
						if (validatedLimit != null) {
							uriBuilder.queryParam("limit", validatedLimit);
						}
						return uriBuilder.build();
					})
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenProvider.getAccessToken())
					.header(ACCOUNT_HEADER, Long.toString(accountSeq))
					.retrieve()
					.body(TossOrderListApiResponse.class);
			return convertListResponse(
					response, accountSeq, validatedStatus, validatedSymbol, from, to);
		} catch (RestClientResponseException exception) {
			if (exception.getStatusCode().value() == 404) {
				throw new OrderHistoryNotFoundException("지정한 계좌를 찾을 수 없습니다.");
			}
			throw new OrderHistoryServiceException(
					"토스증권 주문 목록 조회에 실패했습니다. HTTP 상태: "
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
	 * 주문 목록 그룹이 반드시 진행 중 또는 종료 상태인지 확인합니다.
	 *
	 * @param status 검사할 주문 목록 그룹
	 * @return 검증된 주문 목록 그룹
	 */
	private OrderListStatus validateListStatus(OrderListStatus status) {
		if (status == null) {
			throw new OrderHistoryRequestException("주문 목록 상태는 OPEN 또는 CLOSED여야 합니다.");
		}
		return status;
	}

	/**
	 * 선택 종목 필터의 허용 문자를 확인하고 영문자를 대문자로 정규화합니다.
	 *
	 * @param symbol 검사할 선택 종목 코드
	 * @return 대문자로 정규화한 종목 코드이며 필터가 없으면 null
	 */
	private String validateOptionalRequestSymbol(String symbol) {
		if (symbol == null) {
			return null;
		}
		if (symbol.isBlank()
				|| symbol.length() > MAX_SYMBOL_LENGTH
				|| !SYMBOL_PATTERN.matcher(symbol).matches()) {
			throw new OrderHistoryRequestException("종목 코드 형식이 올바르지 않습니다.");
		}
		return symbol.toUpperCase(Locale.ROOT);
	}

	/**
	 * 조회 시작일이 종료일보다 늦지 않은지 확인합니다.
	 *
	 * @param from 선택 조회 시작일
	 * @param to 선택 조회 종료일
	 */
	private void validateDateRange(LocalDate from, LocalDate to) {
		if (from != null && to != null && from.isAfter(to)) {
			throw new OrderHistoryRequestException("조회 시작일은 종료일보다 늦을 수 없습니다.");
		}
	}

	/**
	 * 종료 주문에서만 페이지 커서를 허용하고 길이와 제어문자를 검사합니다.
	 *
	 * @param status 주문 목록 그룹
	 * @param cursor 검사할 선택 페이지 커서
	 * @return 검증된 커서이며 입력이 없으면 null
	 */
	private String validateCursor(OrderListStatus status, String cursor) {
		if (cursor == null) {
			return null;
		}
		if (status == OrderListStatus.OPEN) {
			throw new OrderHistoryRequestException("진행 중 주문 조회에는 페이지 커서를 사용할 수 없습니다.");
		}
		if (cursor.isBlank()
				|| cursor.length() > MAX_CURSOR_LENGTH
				|| cursor.codePoints().anyMatch(codePoint ->
						Character.isWhitespace(codePoint) || codePoint < 32 || codePoint == 127)) {
			throw new OrderHistoryRequestException("페이지 커서 형식이 올바르지 않습니다.");
		}
		return cursor;
	}

	/**
	 * 종료 주문의 페이지 크기를 1~100으로 제한하고 기본값 20을 적용합니다.
	 *
	 * @param status 주문 목록 그룹
	 * @param limit 검사할 선택 페이지 크기
	 * @return 종료 주문의 검증된 페이지 크기이며 진행 중 주문이면 null
	 */
	private Integer validateLimit(OrderListStatus status, Integer limit) {
		if (status == OrderListStatus.OPEN) {
			if (limit != null) {
				throw new OrderHistoryRequestException("진행 중 주문 조회에는 페이지 크기를 사용할 수 없습니다.");
			}
			return null;
		}
		int resolvedLimit = limit == null ? DEFAULT_CLOSED_LIMIT : limit;
		if (resolvedLimit < 1 || resolvedLimit > MAX_CLOSED_LIMIT) {
			throw new OrderHistoryRequestException("종료 주문 페이지 크기는 1 이상 100 이하여야 합니다.");
		}
		return resolvedLimit;
	}

	/**
	 * 토스증권 목록 응답의 주문·필터·페이지 관계를 검증해 우리 서버 응답으로 변환합니다.
	 *
	 * @param response 토스증권 주문 목록 원본 응답
	 * @param accountSeq 조회에 사용한 계좌 식별값
	 * @param status 요청한 주문 목록 그룹
	 * @param symbol 요청한 선택 종목 코드
	 * @param from 요청한 선택 시작일
	 * @param to 요청한 선택 종료일
	 * @return 검증과 자료형 변환을 마친 주문 목록
	 */
	private OrderListResponse convertListResponse(
			TossOrderListApiResponse response,
			long accountSeq,
			OrderListStatus status,
			String symbol,
			LocalDate from,
			LocalDate to) {
		try {
			TossOrderPage page = response == null ? null : response.result();
			if (page == null || page.orders() == null || page.hasNext() == null) {
				throw malformedResponse();
			}
			validatePage(status, page.nextCursor(), page.hasNext());

			Set<String> orderIds = new HashSet<>();
			List<OrderDetailResponse> orders = page.orders().stream()
					.map(result -> convertListOrder(
							result, accountSeq, status, symbol, from, to, orderIds))
					.toList();
			return new OrderListResponse(
					accountSeq, status, symbol, from, to, List.copyOf(orders),
					page.nextCursor(), page.hasNext());
		} catch (OrderHistoryServiceException exception) {
			throw malformedListResponse();
		}
	}

	/**
	 * 한 목록 주문을 변환하고 요청 필터 및 중복 식별값과 일치하는지 확인합니다.
	 *
	 * @param result 토스증권 원본 주문
	 * @param accountSeq 조회에 사용한 계좌 식별값
	 * @param status 요청한 주문 목록 그룹
	 * @param symbol 요청한 선택 종목 코드
	 * @param from 요청한 선택 시작일
	 * @param to 요청한 선택 종료일
	 * @param orderIds 같은 페이지에서 이미 확인한 주문 식별값
	 * @return 검증과 변환을 마친 주문 상세
	 */
	private OrderDetailResponse convertListOrder(
			TossOrderResult result,
			long accountSeq,
			OrderListStatus status,
			String symbol,
			LocalDate from,
			LocalDate to,
			Set<String> orderIds) {
		if (result == null
				|| !isValidOrderId(result.orderId())
				|| !orderIds.add(result.orderId())) {
			throw malformedResponse();
		}
		OrderDetailResponse order = convertOrder(result, accountSeq);
		if ((symbol != null && !symbol.equals(order.symbol()))
				|| !belongsToListStatus(status, order.status())
				|| !isWithinDateRange(order.orderedAt(), from, to)) {
			throw malformedResponse();
		}
		return order;
	}

	/**
	 * 목록 그룹에 포함될 수 있는 알려진 주문 상태인지 확인하며 새 상태는 원문 보존을 위해 허용합니다.
	 *
	 * @param listStatus 요청한 주문 목록 그룹
	 * @param orderStatus 변환한 개별 주문 상태
	 * @return 그룹에 포함될 수 있으면 true
	 */
	private boolean belongsToListStatus(OrderListStatus listStatus, OrderStatus orderStatus) {
		if (orderStatus == OrderStatus.UNKNOWN || orderStatus == OrderStatus.PARTIAL_FILLED) {
			return true;
		}
		boolean openStatus = Set.of(
				OrderStatus.PENDING,
				OrderStatus.PENDING_CANCEL,
				OrderStatus.PENDING_REPLACE).contains(orderStatus);
		return listStatus == OrderListStatus.OPEN ? openStatus : !openStatus;
	}

	/**
	 * 주문 시각의 한국 날짜가 요청한 시작일과 종료일 안에 있는지 확인합니다.
	 *
	 * @param orderedAt 주문 접수 시각
	 * @param from 선택 조회 시작일
	 * @param to 선택 조회 종료일
	 * @return 요청한 날짜 범위 안이면 true
	 */
	private boolean isWithinDateRange(OffsetDateTime orderedAt, LocalDate from, LocalDate to) {
		LocalDate orderedDate = orderedAt.toLocalDate();
		return (from == null || !orderedDate.isBefore(from))
				&& (to == null || !orderedDate.isAfter(to));
	}

	/**
	 * 진행 중 주문과 종료 주문의 다음 페이지 값이 공식 명세와 일치하는지 확인합니다.
	 *
	 * @param status 요청한 주문 목록 그룹
	 * @param nextCursor 토스증권이 반환한 다음 페이지 커서
	 * @param hasNext 토스증권이 반환한 다음 페이지 존재 여부
	 */
	private void validatePage(OrderListStatus status, String nextCursor, boolean hasNext) {
		if (status == OrderListStatus.OPEN && (hasNext || nextCursor != null)) {
			throw malformedResponse();
		}
		if (hasNext != (nextCursor != null) || (nextCursor != null && !isValidCursor(nextCursor))) {
			throw malformedResponse();
		}
	}

	/**
	 * 토스증권이 반환한 페이지 커서가 로그나 HTTP 처리에 위험한 문자를 포함하지 않는지 확인합니다.
	 *
	 * @param cursor 검사할 응답 페이지 커서
	 * @return 안전한 형식이면 true
	 */
	private boolean isValidCursor(String cursor) {
		return !cursor.isBlank()
				&& cursor.length() <= MAX_CURSOR_LENGTH
				&& cursor.codePoints().noneMatch(codePoint ->
						Character.isWhitespace(codePoint) || codePoint < 32 || codePoint == 127);
	}

	/**
	 * 토스증권 원본 주문 식별값이 상세 조회 경로에 안전한 형식인지 확인합니다.
	 *
	 * @param orderId 검사할 원본 주문 식별값
	 * @return 안전한 형식이면 true
	 */
	private boolean isValidOrderId(String orderId) {
		return orderId != null
				&& !orderId.isBlank()
				&& orderId.length() <= MAX_ORDER_ID_LENGTH
				&& orderId.codePoints().noneMatch(codePoint ->
						Character.isWhitespace(codePoint) || codePoint < 32 || codePoint == 127);
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
		if (result == null
				|| !requestedOrderId.equals(result.orderId())
				|| !isValidOrderId(result.orderId())) {
			throw malformedResponse();
		}
		return convertOrder(result, accountSeq);
	}

	/**
	 * 토스증권 원본 주문의 필수값과 값 사이 관계를 검증해 우리 서버 응답으로 변환합니다.
	 *
	 * @param result 토스증권 원본 주문
	 * @param accountSeq 조회에 사용한 계좌 식별값
	 * @return 검증과 자료형 변환을 마친 주문 상세
	 */
	private OrderDetailResponse convertOrder(TossOrderResult result, long accountSeq) {

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
		if (symbol == null
				|| symbol.length() > MAX_SYMBOL_LENGTH
				|| !SYMBOL_PATTERN.matcher(symbol).matches()) {
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

	/**
	 * 실제 주문 값은 포함하지 않는 주문 목록 응답 형식 오류를 만듭니다.
	 *
	 * @return 안전한 토스증권 주문 목록 응답 오류
	 */
	private OrderHistoryServiceException malformedListResponse() {
		return new OrderHistoryServiceException("토스증권 주문 목록 응답 형식이 올바르지 않습니다.");
	}
}
