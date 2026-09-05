package com.jusika.backend.toss.orderhistory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.jusika.backend.orderhistory.OrderHistoryRequestException;
import com.jusika.backend.orderhistory.OrderHistoryServiceException;
import com.jusika.backend.orderhistory.OrderListResponse;
import com.jusika.backend.orderhistory.OrderListStatus;
import com.jusika.backend.orderhistory.OrderStatus;
import com.jusika.backend.toss.TossApiProperties;
import com.jusika.backend.toss.auth.TossAccessTokenProvider;
import com.jusika.backend.toss.auth.TossAuthClient;
import com.jusika.backend.toss.orderhistory.TossOrderHistoryApiResponse.TossExecution;
import com.jusika.backend.toss.orderhistory.TossOrderHistoryApiResponse.TossOrderResult;
import com.jusika.backend.toss.orderhistory.TossOrderListApiResponse.TossOrderPage;

/**
 * 실제 주문 작업 없이 가짜 HTTP 서버로 토스증권 주문 목록과 페이지 규칙을 검사합니다.
 */
class TossOrderListClientTests {

	private static final String BASE_URL = "https://toss.example";
	private static final String ACCESS_TOKEN = "노출되면-안되는-테스트-토큰";
	private static final String FIRST_ORDER_ID = "order_list_test_001";
	private static final String SECOND_ORDER_ID = "order_list_test_002";
	private static final String NEXT_CURSOR = "next_cursor_test_001";
	private static final long ACCOUNT_SEQ = 1L;

	private MockRestServiceServer server;
	private TossOrderHistoryClient historyClient;

	/**
	 * 각 테스트에서 실제 네트워크 대신 사용할 가짜 토스증권 서버를 준비합니다.
	 */
	@BeforeEach
	void 각_테스트에_필요한_가짜_주문_목록_서버를_준비한다() {
		RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
		server = MockRestServiceServer.bindTo(builder).build();
		RestClient restClient = builder.build();
		TossAuthClient authClient = new TossAuthClient(restClient, 테스트_인증정보를_만든다());
		Clock clock = Clock.fixed(Instant.parse("2026-09-06T00:00:00Z"), ZoneOffset.UTC);
		TossAccessTokenProvider tokenProvider = new TossAccessTokenProvider(authClient, clock);
		historyClient = new TossOrderHistoryClient(restClient, tokenProvider);
	}

	/**
	 * 진행 중 주문은 종목과 날짜만 전송하고 모든 주문을 페이지 없이 변환하는지 검사합니다.
	 */
	@Test
	@DisplayName("진행 중 주문을 종목과 날짜로 필터링해 전량 조회한다")
	void 진행_중_주문을_종목과_날짜로_필터링해_전량_조회한다() {
		정상_토큰_발급_응답을_준비한다();
		server.expect(requestTo(BASE_URL
				+ "/api/v1/orders?status=OPEN&symbol=AAPL&from=2026-03-01&to=2026-03-31"))
				.andExpect(method(HttpMethod.GET))
				.andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
				.andExpect(header("X-Tossinvest-Account", Long.toString(ACCOUNT_SEQ)))
				.andRespond(withSuccess(목록_응답_JSON(
						List.of(
								주문_JSON(FIRST_ORDER_ID, "AAPL", "PENDING", "0"),
								주문_JSON(SECOND_ORDER_ID, "AAPL", "PARTIAL_FILLED", "2")),
						null,
						false), MediaType.APPLICATION_JSON));

		OrderListResponse response = historyClient.getOrders(
				ACCOUNT_SEQ,
				OrderListStatus.OPEN,
				"aapl",
				LocalDate.parse("2026-03-01"),
				LocalDate.parse("2026-03-31"),
				null,
				null);

		assertThat(response.accountSeq()).isEqualTo(ACCOUNT_SEQ);
		assertThat(response.listStatus()).isEqualTo(OrderListStatus.OPEN);
		assertThat(response.symbol()).isEqualTo("AAPL");
		assertThat(response.orders()).hasSize(2);
		assertThat(response.orders().get(0).orderId()).isEqualTo(FIRST_ORDER_ID);
		assertThat(response.orders().get(0).status()).isEqualTo(OrderStatus.PENDING);
		assertThat(response.orders().get(1).status()).isEqualTo(OrderStatus.PARTIAL_FILLED);
		assertThat(response.nextCursor()).isNull();
		assertThat(response.hasNext()).isFalse();
		server.verify();
	}

	/**
	 * 종료 주문은 사용자가 보낸 커서와 페이지 크기로 다음 페이지 정보를 보존하는지 검사합니다.
	 */
	@Test
	@DisplayName("종료된 주문을 커서와 페이지 크기로 조회한다")
	void 종료된_주문을_커서와_페이지_크기로_조회한다() {
		정상_토큰_발급_응답을_준비한다();
		server.expect(requestTo(BASE_URL
				+ "/api/v1/orders?status=CLOSED&cursor=previous_cursor&limit=50"))
				.andRespond(withSuccess(목록_응답_JSON(
						List.of(주문_JSON(FIRST_ORDER_ID, "005930", "FILLED", "10")),
						NEXT_CURSOR,
						true), MediaType.APPLICATION_JSON));

		OrderListResponse response = historyClient.getOrders(
				ACCOUNT_SEQ, OrderListStatus.CLOSED, null, null, null, "previous_cursor", 50);

		assertThat(response.orders()).singleElement()
				.satisfies(order -> {
					assertThat(order.orderId()).isEqualTo(FIRST_ORDER_ID);
					assertThat(order.status()).isEqualTo(OrderStatus.FILLED);
					assertThat(order.execution().filledQuantity()).isEqualByComparingTo("10");
				});
		assertThat(response.nextCursor()).isEqualTo(NEXT_CURSOR);
		assertThat(response.hasNext()).isTrue();
		server.verify();
	}

	/**
	 * 종료 주문의 페이지 크기를 생략하면 공식 기본값 20을 전송하고 빈 목록을 허용하는지 검사합니다.
	 */
	@Test
	@DisplayName("종료 주문의 기본 페이지 크기와 빈 목록을 처리한다")
	void 종료_주문의_기본_페이지_크기와_빈_목록을_처리한다() {
		정상_토큰_발급_응답을_준비한다();
		server.expect(requestTo(BASE_URL + "/api/v1/orders?status=CLOSED&limit=20"))
				.andRespond(withSuccess(목록_응답_JSON(List.of(), null, false),
						MediaType.APPLICATION_JSON));

		OrderListResponse response = historyClient.getOrders(
				ACCOUNT_SEQ, OrderListStatus.CLOSED, null, null, null, null, null);

		assertThat(response.orders()).isEmpty();
		assertThat(response.nextCursor()).isNull();
		assertThat(response.hasNext()).isFalse();
		server.verify();
	}

	/**
	 * 잘못된 상태·종목·날짜 범위는 인증이나 토스증권 조회 전에 차단하는지 검사합니다.
	 */
	@Test
	@DisplayName("잘못된 주문 목록 기본 조건을 외부 호출 전에 차단한다")
	void 잘못된_주문_목록_기본_조건을_외부_호출_전에_차단한다() {
		assertThatThrownBy(() -> historyClient.getOrders(
				ACCOUNT_SEQ, null, null, null, null, null, null))
				.isInstanceOf(OrderHistoryRequestException.class)
				.hasMessage("주문 목록 상태는 OPEN 또는 CLOSED여야 합니다.");
		assertThatThrownBy(() -> historyClient.getOrders(
				ACCOUNT_SEQ, OrderListStatus.OPEN, "AAPL/../../", null, null, null, null))
				.isInstanceOf(OrderHistoryRequestException.class)
				.hasMessage("종목 코드 형식이 올바르지 않습니다.");
		assertThatThrownBy(() -> historyClient.getOrders(
				ACCOUNT_SEQ,
				OrderListStatus.OPEN,
				null,
				LocalDate.parse("2026-04-01"),
				LocalDate.parse("2026-03-31"),
				null,
				null))
				.isInstanceOf(OrderHistoryRequestException.class)
				.hasMessage("조회 시작일은 종료일보다 늦을 수 없습니다.");
		server.verify();
	}

	/**
	 * 진행 중 주문의 무시되는 페이지 값과 종료 주문의 범위를 벗어난 크기를 차단하는지 검사합니다.
	 */
	@Test
	@DisplayName("주문 목록 상태별 잘못된 페이지 조건을 차단한다")
	void 주문_목록_상태별_잘못된_페이지_조건을_차단한다() {
		assertThatThrownBy(() -> historyClient.getOrders(
				ACCOUNT_SEQ, OrderListStatus.OPEN, null, null, null, "cursor", null))
				.isInstanceOf(OrderHistoryRequestException.class)
				.hasMessage("진행 중 주문 조회에는 페이지 커서를 사용할 수 없습니다.");
		assertThatThrownBy(() -> historyClient.getOrders(
				ACCOUNT_SEQ, OrderListStatus.OPEN, null, null, null, null, 20))
				.isInstanceOf(OrderHistoryRequestException.class)
				.hasMessage("진행 중 주문 조회에는 페이지 크기를 사용할 수 없습니다.");
		assertThatThrownBy(() -> historyClient.getOrders(
				ACCOUNT_SEQ, OrderListStatus.CLOSED, null, null, null, null, 101))
				.isInstanceOf(OrderHistoryRequestException.class)
				.hasMessage("종료 주문 페이지 크기는 1 이상 100 이하여야 합니다.");
		server.verify();
	}

	/**
	 * 진행 중 목록에 다음 페이지가 있거나 종료 목록의 커서와 표시가 모순되면 거절하는지 검사합니다.
	 */
	@Test
	@DisplayName("모순된 주문 목록 페이지 응답을 거절한다")
	void 모순된_주문_목록_페이지_응답을_거절한다() {
		정상_토큰_발급_응답을_준비한다();
		server.expect(requestTo(BASE_URL + "/api/v1/orders?status=OPEN"))
				.andRespond(withSuccess(목록_응답_JSON(List.of(), NEXT_CURSOR, true),
						MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> historyClient.getOrders(
				ACCOUNT_SEQ, OrderListStatus.OPEN, null, null, null, null, null))
				.isInstanceOf(OrderHistoryServiceException.class)
				.hasMessage("토스증권 주문 목록 응답 형식이 올바르지 않습니다.");
		server.verify();
	}

	/**
	 * 요청 종목과 다른 주문이나 같은 식별값의 중복 주문을 다른 주문으로 오인하지 않는지 검사합니다.
	 */
	@Test
	@DisplayName("필터 불일치와 중복 주문 식별값 응답을 거절한다")
	void 필터_불일치와_중복_주문_식별값_응답을_거절한다() {
		정상_토큰_발급_응답을_준비한다();
		server.expect(requestTo(BASE_URL + "/api/v1/orders?status=OPEN&symbol=AAPL"))
				.andRespond(withSuccess(목록_응답_JSON(
						List.of(주문_JSON(FIRST_ORDER_ID, "MSFT", "PENDING", "0")),
						null,
						false), MediaType.APPLICATION_JSON));
		server.expect(requestTo(BASE_URL + "/api/v1/orders?status=OPEN"))
				.andRespond(withSuccess(목록_응답_JSON(
						List.of(
								주문_JSON(FIRST_ORDER_ID, "AAPL", "PENDING", "0"),
								주문_JSON(FIRST_ORDER_ID, "AAPL", "PENDING", "0")),
							null,
							false), MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> historyClient.getOrders(
				ACCOUNT_SEQ, OrderListStatus.OPEN, "AAPL", null, null, null, null))
				.isInstanceOf(OrderHistoryServiceException.class)
				.hasMessage("토스증권 주문 목록 응답 형식이 올바르지 않습니다.");
		assertThatThrownBy(() -> historyClient.getOrders(
				ACCOUNT_SEQ, OrderListStatus.OPEN, null, null, null, null, null))
				.isInstanceOf(OrderHistoryServiceException.class)
				.hasMessage("토스증권 주문 목록 응답 형식이 올바르지 않습니다.");
		server.verify();
	}

	/**
	 * 토스증권 서버 오류 본문의 금융값과 인증정보를 노출하지 않고 HTTP 상태만 전달하는지 검사합니다.
	 */
	@Test
	@DisplayName("주문 목록 서버 오류에서 금융정보를 숨긴다")
	void 주문_목록_서버_오류에서_금융정보를_숨긴다() {
		정상_토큰_발급_응답을_준비한다();
		server.expect(requestTo(BASE_URL + "/api/v1/orders?status=CLOSED&limit=20"))
				.andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
						.contentType(MediaType.APPLICATION_JSON)
						.body("{\"privateAmount\":\"987654321\"}"));

		assertThatThrownBy(() -> historyClient.getOrders(
				ACCOUNT_SEQ, OrderListStatus.CLOSED, null, null, null, null, null))
				.isInstanceOf(OrderHistoryServiceException.class)
				.hasMessage("토스증권 주문 목록 조회에 실패했습니다. HTTP 상태: 500")
				.hasMessageNotContaining("987654321")
				.hasMessageNotContaining(ACCESS_TOKEN);
		server.verify();
	}

	/**
	 * 주문 목록 응답 객체들의 문자열 표현에서 주문·커서·금융값을 숨기는지 검사합니다.
	 */
	@Test
	@DisplayName("주문 목록 객체의 문자열 표현에서 금융정보를 숨긴다")
	void 주문_목록_객체의_문자열_표현에서_금융정보를_숨긴다() {
		TossExecution execution = new TossExecution(
				"987", "654321", "123456", "777", "888",
				"2026-03-28T09:31:15+09:00", "2026-03-30");
		TossOrderResult order = new TossOrderResult(
				FIRST_ORDER_ID, "005930", "BUY", "LIMIT", "DAY", "FILLED",
				"654321", "987", null, "KRW", "2026-03-28T09:30:00+09:00", null,
				execution);
		TossOrderPage page = new TossOrderPage(List.of(order), NEXT_CURSOR, true);
		TossOrderListApiResponse rawResponse = new TossOrderListApiResponse(page);
		OrderListResponse response = new OrderListResponse(
				ACCOUNT_SEQ,
				OrderListStatus.CLOSED,
				null,
				null,
				null,
				List.of(),
				NEXT_CURSOR,
				true);

		assertThat(page.toString())
				.doesNotContain(FIRST_ORDER_ID, NEXT_CURSOR, "654321", "987");
		assertThat(rawResponse.toString())
				.doesNotContain(FIRST_ORDER_ID, NEXT_CURSOR, "654321", "987");
		assertThat(response.toString()).doesNotContain(NEXT_CURSOR);
		server.verify();
	}

	/**
	 * 가짜 인증 서버가 충분한 유효시간의 액세스 토큰을 반환하도록 준비합니다.
	 */
	private void 정상_토큰_발급_응답을_준비한다() {
		server.expect(requestTo(BASE_URL + "/oauth2/token"))
				.andRespond(withSuccess("""
						{
						  "access_token": "%s",
						  "token_type": "Bearer",
						  "expires_in": 86400
						}
						""".formatted(ACCESS_TOKEN), MediaType.APPLICATION_JSON));
	}

	/**
	 * 주문 JSON 조각들을 토스증권 페이지 응답 봉투로 묶습니다.
	 *
	 * @param orders 쉼표로 연결할 주문 JSON 조각
	 * @param nextCursor 다음 페이지 커서
	 * @param hasNext 다음 페이지 존재 여부
	 * @return 가짜 주문 목록 응답 JSON
	 */
	private String 목록_응답_JSON(List<String> orders, String nextCursor, boolean hasNext) {
		String cursorJson = nextCursor == null ? "null" : "\"" + nextCursor + "\"";
		return """
				{
				  "result": {
				    "orders": [%s],
				    "nextCursor": %s,
				    "hasNext": %s
				  }
				}
				""".formatted(String.join(",", orders), cursorJson, hasNext);
	}

	/**
	 * 식별값·종목·상태·체결 수량을 바꿀 수 있는 완전한 주문 JSON을 만듭니다.
	 *
	 * @param orderId 주문 식별값
	 * @param symbol 종목 코드
	 * @param status 주문 상태 코드
	 * @param filledQuantity 누적 체결 수량
	 * @return 가짜 주문 원본 JSON
	 */
	private String 주문_JSON(
			String orderId,
			String symbol,
			String status,
			String filledQuantity) {
		boolean filled = !"0".equals(filledQuantity);
		String averageFilledPrice = filled ? "\"70000\"" : "null";
		String filledAmount = filled ? "\"700000\"" : "null";
		String filledAt = filled ? "\"2026-03-28T09:31:15+09:00\"" : "null";
		return """
				{
				  "orderId": "%s",
				  "symbol": "%s",
				  "side": "BUY",
				  "orderType": "LIMIT",
				  "timeInForce": "DAY",
				  "status": "%s",
				  "price": "70000",
				  "quantity": "10",
				  "orderAmount": null,
				  "currency": "KRW",
				  "orderedAt": "2026-03-28T09:30:00+09:00",
				  "canceledAt": null,
				  "execution": {
				    "filledQuantity": "%s",
				    "averageFilledPrice": %s,
				    "filledAmount": %s,
				    "commission": %s,
				    "tax": %s,
				    "filledAt": %s,
				    "settlementDate": null
				  }
				}
				""".formatted(
				orderId,
				symbol,
				status,
				filledQuantity,
				averageFilledPrice,
				filledAmount,
				filled ? "\"1400\"" : "null",
				filled ? "\"0\"" : "null",
				filledAt);
	}

	/**
	 * 가짜 토스증권 서버와 연결할 테스트용 인증 설정을 만듭니다.
	 *
	 * @return 가짜 서버 주소와 가짜 인증정보가 들어 있는 설정
	 */
	private TossApiProperties 테스트_인증정보를_만든다() {
		return new TossApiProperties(
				URI.create(BASE_URL),
				"테스트-클라이언트-아이디",
				"테스트-클라이언트-비밀키",
				Duration.ofSeconds(3),
				Duration.ofSeconds(5));
	}
}
