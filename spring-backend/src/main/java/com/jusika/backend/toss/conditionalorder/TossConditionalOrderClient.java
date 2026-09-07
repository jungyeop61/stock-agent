package com.jusika.backend.toss.conditionalorder;

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

import com.jusika.backend.conditionalorder.ConditionalOrderConditionStatus;
import com.jusika.backend.conditionalorder.ConditionalOrderConditionType;
import com.jusika.backend.conditionalorder.ConditionalOrderDetailResponse;
import com.jusika.backend.conditionalorder.ConditionalOrderDetailResponse.Condition;
import com.jusika.backend.conditionalorder.ConditionalOrderListResponse;
import com.jusika.backend.conditionalorder.ConditionalOrderListStatus;
import com.jusika.backend.conditionalorder.ConditionalOrderMarket;
import com.jusika.backend.conditionalorder.ConditionalOrderNotFoundException;
import com.jusika.backend.conditionalorder.ConditionalOrderRequestException;
import com.jusika.backend.conditionalorder.ConditionalOrderServiceException;
import com.jusika.backend.conditionalorder.ConditionalOrderStatus;
import com.jusika.backend.conditionalorder.ConditionalOrderType;
import com.jusika.backend.orderpreview.OrderType;
import com.jusika.backend.toss.auth.TossAccessTokenProvider;
import com.jusika.backend.toss.conditionalorder.TossConditionalOrderApiResponse.TossConditionalOrderCondition;
import com.jusika.backend.toss.conditionalorder.TossConditionalOrderApiResponse.TossConditionalOrderResult;
import com.jusika.backend.toss.conditionalorder.TossConditionalOrderListApiResponse.TossConditionalOrderPage;

/**
 * 토스증권 조건 주문 목록과 상세를 읽기 전용으로 호출하고 응답을 안전하게 변환합니다.
 */
@Component
public class TossConditionalOrderClient {

	private static final String ACCOUNT_HEADER = "X-Tossinvest-Account";
	private static final int MAX_ID_LENGTH = 512;
	private static final int MAX_CURSOR_LENGTH = 2048;
	private static final int MAX_DECIMAL_LENGTH = 30;
	private static final int MAX_SYMBOL_LENGTH = 32;
	private static final int DEFAULT_LIMIT = 20;
	private static final int MAX_LIMIT = 100;
	private static final Pattern SYMBOL_PATTERN = Pattern.compile("^[A-Za-z0-9.\\-]+$");
	private static final Pattern CURSOR_PATTERN = Pattern.compile("^[A-Za-z0-9_\\-]+$");
	private static final Pattern POSITIVE_DECIMAL_PATTERN = Pattern.compile("^\\d+(\\.\\d+)?$");
	private static final Pattern SIGNED_DECIMAL_PATTERN = Pattern.compile("^-?\\d+(\\.\\d+)?$");
	private static final Set<ConditionalOrderStatus> OPEN_STATUSES = Set.of(
			ConditionalOrderStatus.WATCHING,
			ConditionalOrderStatus.PAUSED,
			ConditionalOrderStatus.ORDERING,
			ConditionalOrderStatus.ORDERED);
	private static final Set<ConditionalOrderStatus> CLOSED_STATUSES = Set.of(
			ConditionalOrderStatus.COMPLETED,
			ConditionalOrderStatus.EXPIRED);

	private final RestClient restClient;
	private final TossAccessTokenProvider tokenProvider;

	/**
	 * 토스증권 전용 REST 클라이언트와 유효한 토큰 공급자를 전달받습니다.
	 *
	 * @param tossRestClient 토스증권 API 전용 REST 클라이언트
	 * @param tokenProvider 유효한 액세스 토큰을 제공하는 객체
	 */
	public TossConditionalOrderClient(
			RestClient tossRestClient,
			TossAccessTokenProvider tokenProvider) {
		this.restClient = tossRestClient;
		this.tokenProvider = tokenProvider;
	}

	/**
	 * 지정한 계좌의 조건 주문을 상태·종목·페이지 조건에 맞게 조회합니다.
	 * 이 함수는 GET 요청만 사용하므로 조건 주문을 생성·정정·취소하지 않습니다.
	 *
	 * @param accountSeq 계좌 목록 API에서 받은 계좌 식별값
	 * @param status 진행 중 또는 종료된 조건 주문 그룹
	 * @param symbol 선택 종목 코드
	 * @param cursor 선택 다음 페이지 커서
	 * @param limit 선택 페이지 크기이며 생략하면 20
	 * @return 검증과 자료형 변환을 마친 조건 주문 목록
	 */
	public ConditionalOrderListResponse getConditionalOrders(
			long accountSeq,
			ConditionalOrderListStatus status,
			String symbol,
			String cursor,
			Integer limit) {
		validateAccountSeq(accountSeq);
		ConditionalOrderListStatus validatedStatus = validateListStatus(status);
		String validatedSymbol = validateOptionalSymbol(symbol);
		String validatedCursor = validateOptionalCursor(cursor);
		int validatedLimit = validateLimit(limit);

		try {
			TossConditionalOrderListApiResponse response = restClient.get()
					.uri(uriBuilder -> {
						uriBuilder.path("/api/v1/conditional-orders")
								.queryParam("status", validatedStatus.name());
						if (validatedSymbol != null) {
							uriBuilder.queryParam("symbol", validatedSymbol);
						}
						if (validatedCursor != null) {
							uriBuilder.queryParam("cursor", validatedCursor);
						}
						uriBuilder.queryParam("limit", validatedLimit);
						return uriBuilder.build();
					})
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenProvider.getAccessToken())
					.header(ACCOUNT_HEADER, Long.toString(accountSeq))
					.retrieve()
					.body(TossConditionalOrderListApiResponse.class);
			return convertListResponse(
					response, accountSeq, validatedStatus, validatedSymbol);
		} catch (RestClientResponseException exception) {
			if (exception.getStatusCode().value() == 404) {
				throw new ConditionalOrderNotFoundException("지정한 계좌를 찾을 수 없습니다.");
			}
			throw new ConditionalOrderServiceException(
					"토스증권 조건 주문 목록 조회에 실패했습니다. HTTP 상태: "
							+ exception.getStatusCode().value());
		} catch (ConditionalOrderRequestException
				| ConditionalOrderNotFoundException
				| ConditionalOrderServiceException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			throw new ConditionalOrderServiceException(
					"토스증권 조건 주문 조회 서버와 통신하지 못했습니다.");
		}
	}

	/**
	 * 지정한 계좌와 조건 주문 식별값으로 한 건의 전체 상태를 조회합니다.
	 * 이 함수는 GET 요청만 사용하므로 조건 주문을 생성·정정·취소하지 않습니다.
	 *
	 * @param accountSeq 계좌 목록 API에서 받은 계좌 식별값
	 * @param conditionalOrderId 토스증권 조건 주문 식별값
	 * @return 검증과 자료형 변환을 마친 조건 주문 상세
	 */
	public ConditionalOrderDetailResponse getConditionalOrder(
			long accountSeq,
			String conditionalOrderId) {
		validateAccountSeq(accountSeq);
		String validatedId = validateId(conditionalOrderId, "조건 주문 식별값 형식이 올바르지 않습니다.");

		try {
			TossConditionalOrderApiResponse response = restClient.get()
					.uri(uriBuilder -> uriBuilder
							.path("/api/v1/conditional-orders")
							.pathSegment(validatedId)
							.build())
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenProvider.getAccessToken())
					.header(ACCOUNT_HEADER, Long.toString(accountSeq))
					.retrieve()
					.body(TossConditionalOrderApiResponse.class);
			return convertDetailResponse(response, accountSeq, validatedId);
		} catch (RestClientResponseException exception) {
			if (exception.getStatusCode().value() == 404) {
				throw new ConditionalOrderNotFoundException(
						"지정한 계좌에서 조건 주문을 찾을 수 없습니다.");
			}
			throw new ConditionalOrderServiceException(
					"토스증권 조건 주문 상세 조회에 실패했습니다. HTTP 상태: "
							+ exception.getStatusCode().value());
		} catch (ConditionalOrderRequestException
				| ConditionalOrderNotFoundException
				| ConditionalOrderServiceException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			throw new ConditionalOrderServiceException(
					"토스증권 조건 주문 조회 서버와 통신하지 못했습니다.");
		}
	}

	/**
	 * 계좌 식별값이 토스증권 계좌 헤더로 사용할 수 있는 양수인지 확인합니다.
	 *
	 * @param accountSeq 검사할 계좌 식별값
	 */
	private void validateAccountSeq(long accountSeq) {
		if (accountSeq <= 0) {
			throw new ConditionalOrderRequestException("계좌 식별값은 1 이상이어야 합니다.");
		}
	}

	/**
	 * 조건 주문 목록 그룹이 반드시 진행 중 또는 종료 상태인지 확인합니다.
	 *
	 * @param status 검사할 조건 주문 목록 그룹
	 * @return 검증된 조건 주문 목록 그룹
	 */
	private ConditionalOrderListStatus validateListStatus(ConditionalOrderListStatus status) {
		if (status == null) {
			throw new ConditionalOrderRequestException(
					"조건 주문 목록 상태는 OPEN 또는 CLOSED여야 합니다.");
		}
		return status;
	}

	/**
	 * 선택 종목 필터의 허용 문자를 확인하고 영문자를 대문자로 정규화합니다.
	 *
	 * @param symbol 검사할 선택 종목 코드
	 * @return 대문자로 정규화한 종목 코드이며 필터가 없으면 null
	 */
	private String validateOptionalSymbol(String symbol) {
		if (symbol == null) {
			return null;
		}
		if (symbol.isBlank()
				|| symbol.length() > MAX_SYMBOL_LENGTH
				|| !SYMBOL_PATTERN.matcher(symbol).matches()) {
			throw new ConditionalOrderRequestException("종목 코드 형식이 올바르지 않습니다.");
		}
		return symbol.toUpperCase(Locale.ROOT);
	}

	/**
	 * 선택 페이지 커서가 공식 명세의 안전한 문자와 길이를 지키는지 확인합니다.
	 *
	 * @param cursor 검사할 선택 페이지 커서
	 * @return 검증된 커서이며 첫 페이지이면 null
	 */
	private String validateOptionalCursor(String cursor) {
		if (cursor == null) {
			return null;
		}
		if (cursor.isBlank()
				|| cursor.length() > MAX_CURSOR_LENGTH
				|| !CURSOR_PATTERN.matcher(cursor).matches()) {
			throw new ConditionalOrderRequestException("페이지 커서 형식이 올바르지 않습니다.");
		}
		return cursor;
	}

	/**
	 * 페이지 크기를 공식 기본값 20과 허용 범위 1~100으로 정규화합니다.
	 *
	 * @param limit 검사할 선택 페이지 크기
	 * @return 요청에 사용할 페이지 크기
	 */
	private int validateLimit(Integer limit) {
		int normalizedLimit = limit == null ? DEFAULT_LIMIT : limit;
		if (normalizedLimit < 1 || normalizedLimit > MAX_LIMIT) {
			throw new ConditionalOrderRequestException(
					"조건 주문 페이지 크기는 1 이상 100 이하여야 합니다.");
		}
		return normalizedLimit;
	}

	/**
	 * 불투명 식별값의 길이와 HTTP 경로에 사용할 수 없는 제어문자를 확인합니다.
	 *
	 * @param value 검사할 식별값
	 * @param errorMessage 잘못된 식별값에 사용할 안전한 오류 설명
	 * @return 검증된 원본 식별값
	 */
	private String validateId(String value, String errorMessage) {
		if (!isValidId(value)) {
			throw new ConditionalOrderRequestException(errorMessage);
		}
		return value;
	}

	/**
	 * 원본 응답 식별값이 비어 있지 않고 제어문자나 공백을 포함하지 않는지 확인합니다.
	 *
	 * @param value 검사할 식별값
	 * @return 안전한 불투명 식별값이면 true
	 */
	private boolean isValidId(String value) {
		return value != null
				&& !value.isBlank()
				&& value.length() <= MAX_ID_LENGTH
				&& value.codePoints().noneMatch(codePoint ->
						Character.isWhitespace(codePoint) || codePoint < 32 || codePoint == 127);
	}

	/**
	 * 원본 목록의 필수값·페이지 관계·필터 일치 여부를 확인해 우리 응답으로 변환합니다.
	 *
	 * @param response 토스증권 조건 주문 목록 원본 응답
	 * @param accountSeq 조회에 사용한 계좌 식별값
	 * @param status 요청한 조건 주문 목록 그룹
	 * @param symbol 요청한 선택 종목 코드
	 * @return 검증과 변환을 마친 조건 주문 목록
	 */
	private ConditionalOrderListResponse convertListResponse(
			TossConditionalOrderListApiResponse response,
			long accountSeq,
			ConditionalOrderListStatus status,
			String symbol) {
		try {
			TossConditionalOrderPage page = response == null ? null : response.result();
			if (page == null || page.conditionalOrders() == null || page.hasNext() == null) {
				throw malformedDetailResponse();
			}
			validatePage(page.nextCursor(), page.hasNext());

			Set<String> ids = new HashSet<>();
			List<ConditionalOrderDetailResponse> orders = page.conditionalOrders().stream()
					.map(result -> convertListOrder(result, accountSeq, status, symbol, ids))
					.toList();
			return new ConditionalOrderListResponse(
					accountSeq,
					status,
					symbol,
					List.copyOf(orders),
					page.nextCursor(),
					page.hasNext());
		} catch (ConditionalOrderServiceException exception) {
			throw malformedListResponse();
		}
	}

	/**
	 * 목록 항목 한 건을 변환하고 요청 필터·목록 상태·중복 식별값을 확인합니다.
	 *
	 * @param result 토스증권 원본 조건 주문
	 * @param accountSeq 조회에 사용한 계좌 식별값
	 * @param status 요청한 조건 주문 목록 그룹
	 * @param symbol 요청한 선택 종목 코드
	 * @param ids 같은 페이지에서 확인한 조건 주문 식별값
	 * @return 검증과 변환을 마친 조건 주문 상세
	 */
	private ConditionalOrderDetailResponse convertListOrder(
			TossConditionalOrderResult result,
			long accountSeq,
			ConditionalOrderListStatus status,
			String symbol,
			Set<String> ids) {
		if (result == null
				|| !isValidId(result.conditionalOrderId())
				|| !ids.add(result.conditionalOrderId())) {
			throw malformedDetailResponse();
		}
		ConditionalOrderDetailResponse order = convertOrder(result, accountSeq);
		if ((symbol != null && !symbol.equals(order.symbol()))
				|| !belongsToListStatus(status, order.status())) {
			throw malformedDetailResponse();
		}
		return order;
	}

	/**
	 * 다음 페이지 표시와 커서 존재 여부가 서로 일치하고 커서 형식이 안전한지 확인합니다.
	 *
	 * @param nextCursor 토스증권이 반환한 다음 페이지 커서
	 * @param hasNext 다음 페이지 존재 여부
	 */
	private void validatePage(String nextCursor, boolean hasNext) {
		if (hasNext != (nextCursor != null)
				|| (nextCursor != null
				&& (nextCursor.length() > MAX_CURSOR_LENGTH
				|| !CURSOR_PATTERN.matcher(nextCursor).matches()))) {
			throw malformedDetailResponse();
		}
	}

	/**
	 * 개별 조건 주문 상태가 요청한 진행 중 또는 종료 그룹에 포함되는지 확인합니다.
	 *
	 * @param listStatus 요청한 목록 그룹
	 * @param orderStatus 토스증권이 반환한 조건 주문 상태
	 * @return 요청 그룹에 속하면 true
	 */
	private boolean belongsToListStatus(
			ConditionalOrderListStatus listStatus,
			ConditionalOrderStatus orderStatus) {
		return listStatus == ConditionalOrderListStatus.OPEN
				? OPEN_STATUSES.contains(orderStatus)
				: CLOSED_STATUSES.contains(orderStatus);
	}

	/**
	 * 상세 응답 식별값이 요청값과 같은지 확인한 뒤 안전한 응답으로 변환합니다.
	 *
	 * @param response 토스증권 조건 주문 상세 원본 응답
	 * @param accountSeq 조회에 사용한 계좌 식별값
	 * @param requestedId 요청에 사용한 조건 주문 식별값
	 * @return 검증과 변환을 마친 조건 주문 상세
	 */
	private ConditionalOrderDetailResponse convertDetailResponse(
			TossConditionalOrderApiResponse response,
			long accountSeq,
			String requestedId) {
		TossConditionalOrderResult result = response == null ? null : response.result();
		if (result == null || !requestedId.equals(result.conditionalOrderId())) {
			throw malformedDetailResponse();
		}
		return convertOrder(result, accountSeq);
	}

	/**
	 * 조건 주문 원본 필드와 필드 사이 관계를 검증해 우리 서버 응답으로 변환합니다.
	 *
	 * @param result 토스증권 원본 조건 주문
	 * @param accountSeq 조회에 사용한 계좌 식별값
	 * @return 검증과 자료형 변환을 마친 조건 주문 상세
	 */
	private ConditionalOrderDetailResponse convertOrder(
			TossConditionalOrderResult result,
			long accountSeq) {
		if (!isValidId(result.conditionalOrderId())) {
			throw malformedDetailResponse();
		}
		ConditionalOrderType type = parseEnum(result.type(), ConditionalOrderType.class);
		ConditionalOrderStatus status = parseEnum(result.status(), ConditionalOrderStatus.class);
		String symbol = validateResponseSymbol(result.symbol());
		ConditionalOrderMarket market = parseEnum(result.market(), ConditionalOrderMarket.class);
		BigDecimal quantity = parsePositiveDecimal(result.quantity());
		OrderType orderType = parseEnum(result.orderType(), OrderType.class);
		LocalDate expireDate = parseOptionalDate(result.expireDate());
		Condition first = convertCondition(result.first(), orderType);
		Condition second = result.second() == null
				? null
				: convertCondition(result.second(), orderType);
		validateConditionStructure(type, first, second);

		return new ConditionalOrderDetailResponse(
				accountSeq,
				result.conditionalOrderId(),
				type,
				status,
				symbol,
				market,
				quantity,
				orderType,
				expireDate,
				first,
				second,
				parseRequiredDateTime(result.createdAt()));
	}

	/**
	 * 한 감시 조건의 숫자·상태·발동 주문 식별값을 변환하고 유형별 필수값을 확인합니다.
	 *
	 * @param condition 토스증권 원본 감시 조건
	 * @param orderType 조건 발동 후 제출할 주문 유형
	 * @return 검증과 변환을 마친 감시 조건
	 */
	private Condition convertCondition(
			TossConditionalOrderCondition condition,
			OrderType orderType) {
		if (condition == null) {
			throw malformedDetailResponse();
		}
		ConditionalOrderConditionType type = parseEnum(
				condition.type(), ConditionalOrderConditionType.class);
		ConditionalOrderConditionStatus status = parseEnum(
				condition.status(), ConditionalOrderConditionStatus.class);
		BigDecimal triggerPrice = parseOptionalPositiveDecimal(condition.triggerPrice());
		BigDecimal targetProfitRate = parseOptionalSignedDecimal(condition.targetProfitRate());
		BigDecimal orderPrice = parseOptionalPositiveDecimal(condition.orderPrice());
		String triggeredOrderId = condition.triggeredOrderId() == null
				? null
				: validateResponseId(condition.triggeredOrderId());

		if ((type == ConditionalOrderConditionType.STOP
				&& (triggerPrice == null || targetProfitRate != null))
				|| (type == ConditionalOrderConditionType.PROFIT_RATE
				&& (targetProfitRate == null || triggerPrice != null))
				|| (orderType == OrderType.LIMIT && orderPrice == null)
				|| (orderType == OrderType.MARKET && orderPrice != null)) {
			throw malformedDetailResponse();
		}

		return new Condition(
				type, status, triggerPrice, targetProfitRate, orderPrice, triggeredOrderId);
	}

	/**
	 * 단일 조건은 두 번째 조건이 없고 OCO·OTO는 같은 유형의 두 조건인지 확인합니다.
	 *
	 * @param type 조건 주문 구성 유형
	 * @param first 첫 번째 감시 조건
	 * @param second 선택 두 번째 감시 조건
	 */
	private void validateConditionStructure(
			ConditionalOrderType type,
			Condition first,
			Condition second) {
		if ((type == ConditionalOrderType.SINGLE && second != null)
				|| (type != ConditionalOrderType.SINGLE
				&& (second == null || first.type() != second.type()))) {
			throw malformedDetailResponse();
		}
	}

	/**
	 * 응답 종목 코드의 허용 문자를 확인하고 영문자를 대문자로 정규화합니다.
	 *
	 * @param symbol 검사할 국내 또는 미국 주식 종목 코드
	 * @return 대문자로 정규화한 종목 코드
	 */
	private String validateResponseSymbol(String symbol) {
		if (symbol == null
				|| symbol.length() > MAX_SYMBOL_LENGTH
				|| !SYMBOL_PATTERN.matcher(symbol).matches()) {
			throw malformedDetailResponse();
		}
		return symbol.toUpperCase(Locale.ROOT);
	}

	/**
	 * 응답의 선택 발동 주문 식별값이 안전한 불투명 문자열인지 확인합니다.
	 *
	 * @param value 검사할 발동 주문 식별값
	 * @return 검증된 원본 식별값
	 */
	private String validateResponseId(String value) {
		if (!isValidId(value)) {
			throw malformedDetailResponse();
		}
		return value;
	}

	/**
	 * 공식 코드 문자열을 지정한 열거형으로 변환하고 알 수 없는 값은 응답 오류로 처리합니다.
	 *
	 * @param value 변환할 원본 코드
	 * @param enumType 변환 대상 열거형 클래스
	 * @param <E> 변환 대상 열거형 자료형
	 * @return 변환된 열거형 값
	 */
	private <E extends Enum<E>> E parseEnum(String value, Class<E> enumType) {
		try {
			return Enum.valueOf(enumType, value);
		} catch (NullPointerException | IllegalArgumentException exception) {
			throw malformedDetailResponse();
		}
	}

	/**
	 * 필수 양수 문자열을 정확한 십진수로 변환합니다.
	 *
	 * @param value 변환할 필수 숫자 문자열
	 * @return 0보다 큰 십진수
	 */
	private BigDecimal parsePositiveDecimal(String value) {
		BigDecimal decimal = parseDecimal(value, POSITIVE_DECIMAL_PATTERN);
		if (decimal.signum() <= 0) {
			throw malformedDetailResponse();
		}
		return decimal;
	}

	/**
	 * 선택 양수 문자열이 있으면 십진수로 변환하고 없으면 null을 유지합니다.
	 *
	 * @param value 변환할 선택 숫자 문자열
	 * @return 0보다 큰 십진수이며 원본이 null이면 null
	 */
	private BigDecimal parseOptionalPositiveDecimal(String value) {
		return value == null ? null : parsePositiveDecimal(value);
	}

	/**
	 * 선택 부호 있는 수익률 문자열이 있으면 십진수로 변환하고 없으면 null을 유지합니다.
	 *
	 * @param value 변환할 선택 수익률 문자열
	 * @return 부호를 보존한 십진수이며 원본이 null이면 null
	 */
	private BigDecimal parseOptionalSignedDecimal(String value) {
		return value == null ? null : parseDecimal(value, SIGNED_DECIMAL_PATTERN);
	}

	/**
	 * 숫자 문자열의 길이와 형식을 확인한 뒤 정확한 BigDecimal로 변환합니다.
	 *
	 * @param value 변환할 필수 숫자 문자열
	 * @param pattern 허용할 숫자 형식
	 * @return 변환된 십진수
	 */
	private BigDecimal parseDecimal(String value, Pattern pattern) {
		try {
			if (value == null
					|| value.isBlank()
					|| value.length() > MAX_DECIMAL_LENGTH
					|| !pattern.matcher(value).matches()) {
				throw malformedDetailResponse();
			}
			return new BigDecimal(value);
		} catch (NumberFormatException exception) {
			throw malformedDetailResponse();
		}
	}

	/**
	 * 선택 ISO 날짜를 LocalDate로 변환하고 값이 없으면 null을 유지합니다.
	 *
	 * @param value 변환할 선택 만료일 문자열
	 * @return 변환된 날짜이며 원본이 null이면 null
	 */
	private LocalDate parseOptionalDate(String value) {
		try {
			return value == null ? null : LocalDate.parse(value);
		} catch (DateTimeParseException exception) {
			throw malformedDetailResponse();
		}
	}

	/**
	 * 필수 ISO 8601 등록 시각을 OffsetDateTime으로 변환합니다.
	 *
	 * @param value 변환할 필수 등록 시각 문자열
	 * @return 변환된 오프셋 포함 시각
	 */
	private OffsetDateTime parseRequiredDateTime(String value) {
		try {
			if (value == null) {
				throw malformedDetailResponse();
			}
			return OffsetDateTime.parse(value);
		} catch (DateTimeParseException exception) {
			throw malformedDetailResponse();
		}
	}

	/**
	 * 실제 조건 주문 값을 포함하지 않는 상세 응답 형식 오류를 만듭니다.
	 *
	 * @return 안전한 토스증권 조건 주문 상세 응답 오류
	 */
	private ConditionalOrderServiceException malformedDetailResponse() {
		return new ConditionalOrderServiceException(
				"토스증권 조건 주문 상세 응답 형식이 올바르지 않습니다.");
	}

	/**
	 * 실제 조건 주문과 커서를 포함하지 않는 목록 응답 형식 오류를 만듭니다.
	 *
	 * @return 안전한 토스증권 조건 주문 목록 응답 오류
	 */
	private ConditionalOrderServiceException malformedListResponse() {
		return new ConditionalOrderServiceException(
				"토스증권 조건 주문 목록 응답 형식이 올바르지 않습니다.");
	}
}
