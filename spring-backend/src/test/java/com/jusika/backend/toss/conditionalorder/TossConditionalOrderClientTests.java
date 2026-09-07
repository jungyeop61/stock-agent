package com.jusika.backend.toss.conditionalorder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.math.BigDecimal;
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

import com.jusika.backend.conditionalorder.ConditionalOrderDetailResponse;
import com.jusika.backend.conditionalorder.ConditionalOrderListResponse;
import com.jusika.backend.conditionalorder.ConditionalOrderListStatus;
import com.jusika.backend.conditionalorder.ConditionalOrderNotFoundException;
import com.jusika.backend.conditionalorder.ConditionalOrderRequestException;
import com.jusika.backend.conditionalorder.ConditionalOrderServiceException;
import com.jusika.backend.conditionalorder.ConditionalOrderStatus;
import com.jusika.backend.conditionalorder.ConditionalOrderType;
import com.jusika.backend.conditionalorder.ConditionalOrderCreationResponse;
import com.jusika.backend.conditionalorder.OcoConditionalOrderSubmissionRequest;
import com.jusika.backend.conditionalorder.OtoConditionalOrderSubmissionRequest;
import com.jusika.backend.conditionalorder.SingleConditionalOrderSubmissionRequest;
import com.jusika.backend.orderexecution.OrderSubmissionException;
import com.jusika.backend.orderpreview.OrderSide;
import com.jusika.backend.orderpreview.OrderType;
import com.jusika.backend.toss.TossApiProperties;
import com.jusika.backend.toss.auth.TossAccessTokenProvider;
import com.jusika.backend.toss.auth.TossAuthClient;
import com.jusika.backend.toss.conditionalorder.TossConditionalOrderApiResponse.TossConditionalOrderCondition;
import com.jusika.backend.toss.conditionalorder.TossConditionalOrderApiResponse.TossConditionalOrderResult;
import com.jusika.backend.toss.conditionalorder.TossConditionalOrderListApiResponse.TossConditionalOrderPage;

/**
 * 실제 주문 작업 없이 가짜 HTTP 서버로 조건 주문 목록·상세와 검증 규칙을 검사합니다.
 */
class TossConditionalOrderClientTests {

	private static final String BASE_URL = "https://toss.example";
	private static final String ACCESS_TOKEN = "노출되면-안되는-조건주문-테스트-토큰";
	private static final String FIRST_ID = "conditional_test_001";
	private static final String SECOND_ID = "conditional_test_002";
	private static final String NEXT_CURSOR = "next_conditional_cursor";
	private static final long ACCOUNT_SEQ = 1L;

	private MockRestServiceServer server;
	private TossConditionalOrderClient conditionalOrderClient;

	/**
	 * 각 테스트에서 실제 네트워크 대신 사용할 가짜 토스증권 서버를 준비합니다.
	 */
	@BeforeEach
	void 각_테스트에_필요한_가짜_조건_주문_서버를_준비한다() {
		RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
		server = MockRestServiceServer.bindTo(builder).build();
		RestClient restClient = builder.build();
		TossAuthClient authClient = new TossAuthClient(restClient, 테스트_인증정보를_만든다());
		Clock clock = Clock.fixed(Instant.parse("2026-09-07T00:00:00Z"), ZoneOffset.UTC);
		TossAccessTokenProvider tokenProvider = new TossAccessTokenProvider(authClient, clock);
		conditionalOrderClient = new TossConditionalOrderClient(restClient, tokenProvider);
	}

	/**
	 * 진행 중 조건 주문을 종목·커서·페이지 크기로 조회하고 OCO 두 조건을 변환하는지 검사합니다.
	 */
	@Test
	@DisplayName("진행 중 OCO 조건 주문 목록과 페이지 정보를 조회한다")
	void 진행_중_OCO_조건_주문_목록과_페이지_정보를_조회한다() {
		정상_토큰_발급_응답을_준비한다();
		server.expect(requestTo(BASE_URL
				+ "/api/v1/conditional-orders?status=OPEN&symbol=AAPL"
				+ "&cursor=previous_cursor&limit=50"))
				.andExpect(method(HttpMethod.GET))
				.andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
				.andExpect(header("X-Tossinvest-Account", Long.toString(ACCOUNT_SEQ)))
				.andRespond(withSuccess(목록_응답_JSON(
						List.of(OCO_조건_주문_JSON(FIRST_ID, "AAPL", "WATCHING")),
						NEXT_CURSOR,
						true), MediaType.APPLICATION_JSON));

		ConditionalOrderListResponse response = conditionalOrderClient.getConditionalOrders(
				ACCOUNT_SEQ,
				ConditionalOrderListStatus.OPEN,
				"aapl",
				"previous_cursor",
				50);

		assertThat(response.accountSeq()).isEqualTo(ACCOUNT_SEQ);
		assertThat(response.listStatus()).isEqualTo(ConditionalOrderListStatus.OPEN);
		assertThat(response.symbol()).isEqualTo("AAPL");
		assertThat(response.nextCursor()).isEqualTo(NEXT_CURSOR);
		assertThat(response.hasNext()).isTrue();
		assertThat(response.conditionalOrders()).singleElement().satisfies(order -> {
			assertThat(order.conditionalOrderId()).isEqualTo(FIRST_ID);
			assertThat(order.type()).isEqualTo(ConditionalOrderType.OCO);
			assertThat(order.status()).isEqualTo(ConditionalOrderStatus.WATCHING);
			assertThat(order.quantity()).isEqualByComparingTo("10");
			assertThat(order.first().triggerPrice()).isEqualByComparingTo("210");
			assertThat(order.second().orderPrice()).isEqualByComparingTo("189");
		});
		server.verify();
	}

	/**
	 * 페이지 크기를 생략하면 공식 기본값 20을 사용하고 빈 종료 목록을 허용하는지 검사합니다.
	 */
	@Test
	@DisplayName("종료 조건 주문의 기본 페이지 크기와 빈 목록을 처리한다")
	void 종료_조건_주문의_기본_페이지_크기와_빈_목록을_처리한다() {
		정상_토큰_발급_응답을_준비한다();
		server.expect(requestTo(BASE_URL
				+ "/api/v1/conditional-orders?status=CLOSED&limit=20"))
				.andRespond(withSuccess(목록_응답_JSON(List.of(), null, false),
						MediaType.APPLICATION_JSON));

		ConditionalOrderListResponse response = conditionalOrderClient.getConditionalOrders(
				ACCOUNT_SEQ, ConditionalOrderListStatus.CLOSED, null, null, null);

		assertThat(response.conditionalOrders()).isEmpty();
		assertThat(response.nextCursor()).isNull();
		assertThat(response.hasNext()).isFalse();
		server.verify();
	}

	/**
	 * 단일 시장가 조건 주문 상세를 조회하고 선택 만료일과 발동 주문 식별값을 보존하는지 검사합니다.
	 */
	@Test
	@DisplayName("단일 시장가 조건 주문 상세를 조회한다")
	void 단일_시장가_조건_주문_상세를_조회한다() {
		정상_토큰_발급_응답을_준비한다();
		server.expect(requestTo(BASE_URL + "/api/v1/conditional-orders/" + FIRST_ID))
				.andExpect(method(HttpMethod.GET))
				.andExpect(header("X-Tossinvest-Account", Long.toString(ACCOUNT_SEQ)))
				.andRespond(withSuccess(상세_응답_JSON(
						SINGLE_시장가_조건_주문_JSON(FIRST_ID)), MediaType.APPLICATION_JSON));

		ConditionalOrderDetailResponse response =
				conditionalOrderClient.getConditionalOrder(ACCOUNT_SEQ, FIRST_ID);

		assertThat(response.type()).isEqualTo(ConditionalOrderType.SINGLE);
		assertThat(response.orderType()).isEqualTo(OrderType.MARKET);
		assertThat(response.expireDate()).isEqualTo("2026-09-10");
		assertThat(response.first().orderPrice()).isNull();
		assertThat(response.first().triggeredOrderId()).isEqualTo("triggered_order_001");
		assertThat(response.second()).isNull();
		server.verify();
	}

	/**
	 * 잘못된 계좌·상태·종목·커서·페이지 크기·식별값을 인증 전에 차단하는지 검사합니다.
	 */
	@Test
	@DisplayName("잘못된 조건 주문 조회 요청을 외부 호출 전에 차단한다")
	void 잘못된_조건_주문_조회_요청을_외부_호출_전에_차단한다() {
		assertThatThrownBy(() -> conditionalOrderClient.getConditionalOrders(
				0, ConditionalOrderListStatus.OPEN, null, null, null))
				.isInstanceOf(ConditionalOrderRequestException.class)
				.hasMessage("계좌 식별값은 1 이상이어야 합니다.");
		assertThatThrownBy(() -> conditionalOrderClient.getConditionalOrders(
				ACCOUNT_SEQ, null, null, null, null))
				.isInstanceOf(ConditionalOrderRequestException.class)
				.hasMessage("조건 주문 목록 상태는 OPEN 또는 CLOSED여야 합니다.");
		assertThatThrownBy(() -> conditionalOrderClient.getConditionalOrders(
				ACCOUNT_SEQ, ConditionalOrderListStatus.OPEN, "AAPL/../", null, null))
				.isInstanceOf(ConditionalOrderRequestException.class)
				.hasMessage("종목 코드 형식이 올바르지 않습니다.");
		assertThatThrownBy(() -> conditionalOrderClient.getConditionalOrders(
				ACCOUNT_SEQ, ConditionalOrderListStatus.OPEN, null, "bad cursor", null))
				.isInstanceOf(ConditionalOrderRequestException.class)
				.hasMessage("페이지 커서 형식이 올바르지 않습니다.");
		assertThatThrownBy(() -> conditionalOrderClient.getConditionalOrders(
				ACCOUNT_SEQ, ConditionalOrderListStatus.OPEN, null, null, 101))
				.isInstanceOf(ConditionalOrderRequestException.class)
				.hasMessage("조건 주문 페이지 크기는 1 이상 100 이하여야 합니다.");
		assertThatThrownBy(() -> conditionalOrderClient.getConditionalOrder(
				ACCOUNT_SEQ, "bad id"))
				.isInstanceOf(ConditionalOrderRequestException.class)
				.hasMessage("조건 주문 식별값 형식이 올바르지 않습니다.");
		server.verify();
	}

	/**
	 * 목록 상태 불일치·중복 식별값·모순된 페이지 정보를 신뢰하지 않는지 검사합니다.
	 */
	@Test
	@DisplayName("모순된 조건 주문 목록 응답을 거절한다")
	void 모순된_조건_주문_목록_응답을_거절한다() {
		정상_토큰_발급_응답을_준비한다();
		server.expect(requestTo(BASE_URL
				+ "/api/v1/conditional-orders?status=OPEN&limit=20"))
				.andRespond(withSuccess(목록_응답_JSON(
						List.of(OCO_조건_주문_JSON(FIRST_ID, "AAPL", "COMPLETED")),
						null,
						false), MediaType.APPLICATION_JSON));
		server.expect(requestTo(BASE_URL
				+ "/api/v1/conditional-orders?status=CLOSED&limit=20"))
				.andRespond(withSuccess(목록_응답_JSON(
						List.of(
								OCO_조건_주문_JSON(FIRST_ID, "AAPL", "COMPLETED"),
								OCO_조건_주문_JSON(FIRST_ID, "AAPL", "COMPLETED")),
						NEXT_CURSOR,
						false), MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> conditionalOrderClient.getConditionalOrders(
				ACCOUNT_SEQ, ConditionalOrderListStatus.OPEN, null, null, null))
				.isInstanceOf(ConditionalOrderServiceException.class)
				.hasMessage("토스증권 조건 주문 목록 응답 형식이 올바르지 않습니다.");
		assertThatThrownBy(() -> conditionalOrderClient.getConditionalOrders(
				ACCOUNT_SEQ, ConditionalOrderListStatus.CLOSED, null, null, null))
				.isInstanceOf(ConditionalOrderServiceException.class)
				.hasMessage("토스증권 조건 주문 목록 응답 형식이 올바르지 않습니다.");
		server.verify();
	}

	/**
	 * SINGLE의 두 번째 조건과 지정가의 누락된 주문 가격을 잘못된 상세로 처리하는지 검사합니다.
	 */
	@Test
	@DisplayName("조건 유형과 가격 규칙이 잘못된 상세 응답을 거절한다")
	void 조건_유형과_가격_규칙이_잘못된_상세_응답을_거절한다() {
		정상_토큰_발급_응답을_준비한다();
		String invalidSingle = OCO_조건_주문_JSON(FIRST_ID, "AAPL", "WATCHING")
				.replace("\"type\": \"OCO\"", "\"type\": \"SINGLE\"");
		server.expect(requestTo(BASE_URL + "/api/v1/conditional-orders/" + FIRST_ID))
				.andRespond(withSuccess(상세_응답_JSON(invalidSingle),
						MediaType.APPLICATION_JSON));

		assertThatThrownBy(() ->
				conditionalOrderClient.getConditionalOrder(ACCOUNT_SEQ, FIRST_ID))
				.isInstanceOf(ConditionalOrderServiceException.class)
				.hasMessage("토스증권 조건 주문 상세 응답 형식이 올바르지 않습니다.");
		server.verify();
	}

	/**
	 * 상세 404와 목록 500을 구분하고 서버 본문의 금융정보를 오류 메시지에서 숨기는지 검사합니다.
	 */
	@Test
	@DisplayName("조건 주문 조회 오류에서 식별값과 금융정보를 숨긴다")
	void 조건_주문_조회_오류에서_식별값과_금융정보를_숨긴다() {
		정상_토큰_발급_응답을_준비한다();
		server.expect(requestTo(BASE_URL + "/api/v1/conditional-orders/" + FIRST_ID))
				.andRespond(withStatus(HttpStatus.NOT_FOUND));
		server.expect(requestTo(BASE_URL
				+ "/api/v1/conditional-orders?status=OPEN&limit=20"))
				.andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
						.contentType(MediaType.APPLICATION_JSON)
						.body("{\"privateAmount\":\"987654321\"}"));

		assertThatThrownBy(() ->
				conditionalOrderClient.getConditionalOrder(ACCOUNT_SEQ, FIRST_ID))
				.isInstanceOf(ConditionalOrderNotFoundException.class)
				.hasMessageNotContaining(FIRST_ID)
				.hasMessageNotContaining(ACCESS_TOKEN);
		assertThatThrownBy(() -> conditionalOrderClient.getConditionalOrders(
				ACCOUNT_SEQ, ConditionalOrderListStatus.OPEN, null, null, null))
				.isInstanceOf(ConditionalOrderServiceException.class)
				.hasMessage("토스증권 조건 주문 목록 조회에 실패했습니다. HTTP 상태: 500")
				.hasMessageNotContaining("987654321")
				.hasMessageNotContaining(ACCESS_TOKEN);
		server.verify();
	}

	/**
	 * 조건 주문 관련 응답 객체의 문자열 표현에서 식별값·커서·금융값을 숨기는지 검사합니다.
	 */
	@Test
	@DisplayName("조건 주문 객체의 문자열 표현에서 금융정보를 숨긴다")
	void 조건_주문_객체의_문자열_표현에서_금융정보를_숨긴다() {
		TossConditionalOrderCondition condition = new TossConditionalOrderCondition(
				"STOP", "WATCHING", "987654321", null, "876543210", "triggered_secret");
		TossConditionalOrderResult order = new TossConditionalOrderResult(
				FIRST_ID, "SINGLE", "WATCHING", "005930", "KR", "999", "LIMIT",
				"2026-09-10", condition, null, "2026-09-07T09:00:00+09:00");
		TossConditionalOrderPage page = new TossConditionalOrderPage(
				List.of(order), NEXT_CURSOR, true);

		assertThat(condition.toString()).doesNotContain("987654321", "triggered_secret");
		assertThat(order.toString()).doesNotContain(FIRST_ID, "987654321", "999");
		assertThat(page.toString()).doesNotContain(FIRST_ID, NEXT_CURSOR, "987654321");
		assertThat(new TossConditionalOrderListApiResponse(page).toString())
				.doesNotContain(FIRST_ID, NEXT_CURSOR, "987654321");
		server.verify();
	}

	/** 국내 지정가 단일 조건 주문을 공식 중첩 JSON 형식과 헤더로 보내는지 검사합니다. */
	@Test
	@DisplayName("국내 지정가 단일 조건 주문 생성 본문을 만든다")
	void 국내_지정가_단일_조건_주문_생성_본문을_만든다() {
		정상_토큰_발급_응답을_준비한다();
		SingleConditionalOrderSubmissionRequest request =
				new SingleConditionalOrderSubmissionRequest(
						"conditional-client-001", "005930", BigDecimal.TEN,
						OrderType.LIMIT, LocalDate.parse("2026-09-10"), OrderSide.SELL,
						new BigDecimal("72000"), new BigDecimal("71500"), false);
		server.expect(requestTo(BASE_URL + "/api/v1/conditional-orders"))
				.andExpect(method(HttpMethod.POST))
				.andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
				.andExpect(header("X-Tossinvest-Account", Long.toString(ACCOUNT_SEQ)))
				.andExpect(jsonPath("$.symbol").value("005930"))
				.andExpect(jsonPath("$.type").value("SINGLE"))
				.andExpect(jsonPath("$.quantity").value("10"))
				.andExpect(jsonPath("$.orderType").value("LIMIT"))
				.andExpect(jsonPath("$.clientOrderId").value("conditional-client-001"))
				.andExpect(jsonPath("$.expireDate").value("2026-09-10"))
				.andExpect(jsonPath("$.first.orderSide").value("SELL"))
				.andExpect(jsonPath("$.first.triggerPrice").value("72000"))
				.andExpect(jsonPath("$.first.orderPrice").value("71500"))
				.andExpect(jsonPath("$.second").doesNotExist())
				.andExpect(jsonPath("$.confirmHighValueOrder").value(false))
				.andRespond(withSuccess("""
						{"result":{"conditionalOrderId":"created-conditional-001",
						"clientOrderId":"conditional-client-001"}}
						""", MediaType.APPLICATION_JSON));

		ConditionalOrderCreationResponse response =
				conditionalOrderClient.createSingleConditionalOrder(ACCOUNT_SEQ, request);

		assertThat(response.conditionalOrderId()).isEqualTo("created-conditional-001");
		assertThat(response.clientOrderId()).isEqualTo("conditional-client-001");
		server.verify();
	}

	/** 시장가 단일 조건 주문에서는 지정가와 두 번째 조건을 보내지 않는지 검사합니다. */
	@Test
	@DisplayName("시장가 단일 조건 주문에서 주문가격을 생략한다")
	void 시장가_단일_조건_주문에서_주문가격을_생략한다() {
		정상_토큰_발급_응답을_준비한다();
		SingleConditionalOrderSubmissionRequest request =
				new SingleConditionalOrderSubmissionRequest(
						"conditional-client-002", "AAPL", new BigDecimal("1.5"),
						OrderType.MARKET, LocalDate.parse("2026-09-10"), OrderSide.SELL,
						new BigDecimal("190"), null, true);
		server.expect(requestTo(BASE_URL + "/api/v1/conditional-orders"))
				.andExpect(jsonPath("$.first.orderPrice").doesNotExist())
				.andExpect(jsonPath("$.second").doesNotExist())
				.andExpect(jsonPath("$.confirmHighValueOrder").value(true))
				.andRespond(withSuccess("""
						{"result":{"conditionalOrderId":"created-conditional-002",
						"clientOrderId":"conditional-client-002"}}
						""", MediaType.APPLICATION_JSON));

		ConditionalOrderCreationResponse response =
				conditionalOrderClient.createSingleConditionalOrder(ACCOUNT_SEQ, request);

		assertThat(response.conditionalOrderId()).isEqualTo("created-conditional-002");
		server.verify();
	}

	/** 잘못된 멱등성 식별값과 시장가 주문가격을 토큰 발급 전에 차단하는지 검사합니다. */
	@Test
	@DisplayName("잘못된 단일 조건 주문 생성 요청을 외부 호출 전에 차단한다")
	void 잘못된_단일_조건_주문_생성_요청을_외부_호출_전에_차단한다() {
		SingleConditionalOrderSubmissionRequest badId =
				new SingleConditionalOrderSubmissionRequest(
						"잘못된 멱등키", "005930", BigDecimal.ONE, OrderType.LIMIT,
						LocalDate.parse("2026-09-10"), OrderSide.BUY,
						new BigDecimal("70000"), new BigDecimal("70000"), false);
		SingleConditionalOrderSubmissionRequest badMarketPrice =
				new SingleConditionalOrderSubmissionRequest(
						"valid-client-id", "005930", BigDecimal.ONE, OrderType.MARKET,
						LocalDate.parse("2026-09-10"), OrderSide.BUY,
						new BigDecimal("70000"), new BigDecimal("70000"), false);

		assertThatThrownBy(() ->
				conditionalOrderClient.createSingleConditionalOrder(ACCOUNT_SEQ, badId))
				.isInstanceOf(OrderSubmissionException.class)
				.satisfies(exception -> assertThat(
						((OrderSubmissionException) exception).isSubmissionStateUnknown()).isFalse());
		assertThatThrownBy(() -> conditionalOrderClient.createSingleConditionalOrder(
				ACCOUNT_SEQ, badMarketPrice))
				.isInstanceOf(OrderSubmissionException.class)
				.hasMessage("조건 주문 생성 요청 가격 형식이 올바르지 않습니다.");
		server.verify();
	}

	/** 증권사의 4xx 거절과 5xx 결과 불명을 서로 다르게 분류하는지 검사합니다. */
	@Test
	@DisplayName("조건 주문 생성의 확정 거절과 결과 불명을 구분한다")
	void 조건_주문_생성의_확정_거절과_결과_불명을_구분한다() {
		정상_토큰_발급_응답을_준비한다();
		SingleConditionalOrderSubmissionRequest request =
				new SingleConditionalOrderSubmissionRequest(
						"conditional-client-003", "005930", BigDecimal.ONE,
						OrderType.LIMIT, LocalDate.parse("2026-09-10"), OrderSide.BUY,
						new BigDecimal("70000"), new BigDecimal("70000"), false);
		server.expect(requestTo(BASE_URL + "/api/v1/conditional-orders"))
				.andRespond(withStatus(HttpStatus.UNPROCESSABLE_CONTENT));
		server.expect(requestTo(BASE_URL + "/api/v1/conditional-orders"))
				.andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

		assertThatThrownBy(() ->
				conditionalOrderClient.createSingleConditionalOrder(ACCOUNT_SEQ, request))
				.isInstanceOf(OrderSubmissionException.class)
				.satisfies(exception -> assertThat(
						((OrderSubmissionException) exception).isSubmissionStateUnknown()).isFalse());
		assertThatThrownBy(() ->
				conditionalOrderClient.createSingleConditionalOrder(ACCOUNT_SEQ, request))
				.isInstanceOf(OrderSubmissionException.class)
				.satisfies(exception -> assertThat(
						((OrderSubmissionException) exception).isSubmissionStateUnknown()).isTrue());
		server.verify();
	}

	/** OCO의 두 매도 지정가 조건을 공식 중첩 JSON 형식과 계좌 헤더로 보내는지 검사합니다. */
	@Test
	@DisplayName("OCO 조건 주문 생성 본문에 두 매도 조건을 담는다")
	void OCO_조건_주문_생성_본문에_두_매도_조건을_담는다() {
		정상_토큰_발급_응답을_준비한다();
		OcoConditionalOrderSubmissionRequest request = OCO_생성_요청을_만든다(
				OrderType.LIMIT, OrderSide.SELL, OrderSide.SELL);
		server.expect(requestTo(BASE_URL + "/api/v1/conditional-orders"))
				.andExpect(method(HttpMethod.POST))
				.andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
				.andExpect(header("X-Tossinvest-Account", Long.toString(ACCOUNT_SEQ)))
				.andExpect(jsonPath("$.symbol").value("005930"))
				.andExpect(jsonPath("$.type").value("OCO"))
				.andExpect(jsonPath("$.quantity").value("10"))
				.andExpect(jsonPath("$.orderType").value("LIMIT"))
				.andExpect(jsonPath("$.clientOrderId").value("oco-client-001"))
				.andExpect(jsonPath("$.expireDate").value("2026-09-10"))
				.andExpect(jsonPath("$.first.orderSide").value("SELL"))
				.andExpect(jsonPath("$.first.triggerPrice").value("80000"))
				.andExpect(jsonPath("$.first.orderPrice").value("79000"))
				.andExpect(jsonPath("$.second.orderSide").value("SELL"))
				.andExpect(jsonPath("$.second.triggerPrice").value("65000"))
				.andExpect(jsonPath("$.second.orderPrice").value("64900"))
				.andExpect(jsonPath("$.confirmHighValueOrder").value(false))
				.andRespond(withSuccess("""
						{"result":{"conditionalOrderId":"created-oco-001",
						"clientOrderId":"oco-client-001"}}
						""", MediaType.APPLICATION_JSON));

		ConditionalOrderCreationResponse response =
				conditionalOrderClient.createOcoConditionalOrder(ACCOUNT_SEQ, request);

		assertThat(response.conditionalOrderId()).isEqualTo("created-oco-001");
		assertThat(response.clientOrderId()).isEqualTo("oco-client-001");
		server.verify();
	}

	/** OCO의 시장가 또는 매수 조건을 인증 요청 전에 차단하는지 검사합니다. */
	@Test
	@DisplayName("잘못된 OCO 생성 요청을 외부 호출 전에 차단한다")
	void 잘못된_OCO_생성_요청을_외부_호출_전에_차단한다() {
		OcoConditionalOrderSubmissionRequest market = OCO_생성_요청을_만든다(
				OrderType.MARKET, OrderSide.SELL, OrderSide.SELL);
		OcoConditionalOrderSubmissionRequest buy = OCO_생성_요청을_만든다(
				OrderType.LIMIT, OrderSide.BUY, OrderSide.SELL);

		assertThatThrownBy(() ->
				conditionalOrderClient.createOcoConditionalOrder(ACCOUNT_SEQ, market))
				.isInstanceOf(OrderSubmissionException.class)
				.hasMessage("OCO 조건 주문 생성 요청 형식이 올바르지 않습니다.")
				.satisfies(exception -> assertThat(
						((OrderSubmissionException) exception).isSubmissionStateUnknown()).isFalse());
		assertThatThrownBy(() ->
				conditionalOrderClient.createOcoConditionalOrder(ACCOUNT_SEQ, buy))
				.isInstanceOf(OrderSubmissionException.class)
				.hasMessage("OCO 조건 주문 생성 요청 형식이 올바르지 않습니다.");
		server.verify();
	}

	/** OTO의 선행 매수와 후행 매도를 공식 중첩 JSON 형식과 계좌 헤더로 보냅니다. */
	@Test
	@DisplayName("OTO 조건 주문 생성 본문에 매수와 매도 조건을 담는다")
	void OTO_조건_주문_생성_본문에_매수와_매도_조건을_담는다() {
		정상_토큰_발급_응답을_준비한다();
		OtoConditionalOrderSubmissionRequest request = OTO_생성_요청을_만든다(
				OrderType.LIMIT, OrderSide.BUY, OrderSide.SELL);
		server.expect(requestTo(BASE_URL + "/api/v1/conditional-orders"))
				.andExpect(method(HttpMethod.POST))
				.andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
				.andExpect(header("X-Tossinvest-Account", Long.toString(ACCOUNT_SEQ)))
				.andExpect(jsonPath("$.symbol").value("005930"))
				.andExpect(jsonPath("$.type").value("OTO"))
				.andExpect(jsonPath("$.quantity").value("10"))
				.andExpect(jsonPath("$.orderType").value("LIMIT"))
				.andExpect(jsonPath("$.clientOrderId").value("oto-client-001"))
				.andExpect(jsonPath("$.expireDate").value("2026-09-10"))
				.andExpect(jsonPath("$.first.orderSide").value("BUY"))
				.andExpect(jsonPath("$.first.triggerPrice").value("68000"))
				.andExpect(jsonPath("$.first.orderPrice").value("69000"))
				.andExpect(jsonPath("$.second.orderSide").value("SELL"))
				.andExpect(jsonPath("$.second.triggerPrice").value("79000"))
				.andExpect(jsonPath("$.second.orderPrice").value("80000"))
				.andExpect(jsonPath("$.confirmHighValueOrder").value(false))
				.andRespond(withSuccess("""
						{"result":{"conditionalOrderId":"created-oto-001",
						"clientOrderId":"oto-client-001"}}
						""", MediaType.APPLICATION_JSON));

		ConditionalOrderCreationResponse response =
				conditionalOrderClient.createOtoConditionalOrder(ACCOUNT_SEQ, request);

		assertThat(response.conditionalOrderId()).isEqualTo("created-oto-001");
		assertThat(response.clientOrderId()).isEqualTo("oto-client-001");
		server.verify();
	}

	/** OTO의 시장가 또는 잘못된 조건 순서를 인증 요청 전에 차단합니다. */
	@Test
	@DisplayName("잘못된 OTO 생성 요청을 외부 호출 전에 차단한다")
	void 잘못된_OTO_생성_요청을_외부_호출_전에_차단한다() {
		OtoConditionalOrderSubmissionRequest market = OTO_생성_요청을_만든다(
				OrderType.MARKET, OrderSide.BUY, OrderSide.SELL);
		OtoConditionalOrderSubmissionRequest wrongOrder = OTO_생성_요청을_만든다(
				OrderType.LIMIT, OrderSide.SELL, OrderSide.BUY);

		assertThatThrownBy(() ->
				conditionalOrderClient.createOtoConditionalOrder(ACCOUNT_SEQ, market))
				.isInstanceOf(OrderSubmissionException.class)
				.hasMessage("OTO 조건 주문 생성 요청 형식이 올바르지 않습니다.")
				.satisfies(exception -> assertThat(
						((OrderSubmissionException) exception).isSubmissionStateUnknown()).isFalse());
		assertThatThrownBy(() ->
				conditionalOrderClient.createOtoConditionalOrder(ACCOUNT_SEQ, wrongOrder))
				.isInstanceOf(OrderSubmissionException.class)
				.hasMessage("OTO 조건 주문 생성 요청 형식이 올바르지 않습니다.");
		server.verify();
	}

	/** 반복 테스트에서 사용할 OTO 실제 생성 요청을 만듭니다. */
	private OtoConditionalOrderSubmissionRequest OTO_생성_요청을_만든다(
			OrderType orderType,
			OrderSide firstSide,
			OrderSide secondSide) {
		return new OtoConditionalOrderSubmissionRequest(
				"oto-client-001", "005930", BigDecimal.TEN, orderType,
				LocalDate.parse("2026-09-10"),
				new OtoConditionalOrderSubmissionRequest.Condition(
						firstSide, new BigDecimal("68000"), new BigDecimal("69000")),
				new OtoConditionalOrderSubmissionRequest.Condition(
						secondSide, new BigDecimal("79000"), new BigDecimal("80000")),
				false);
	}

	/** 반복 테스트에서 사용할 OCO 실제 생성 요청을 만듭니다. */
	private OcoConditionalOrderSubmissionRequest OCO_생성_요청을_만든다(
			OrderType orderType,
			OrderSide firstSide,
			OrderSide secondSide) {
		return new OcoConditionalOrderSubmissionRequest(
				"oco-client-001", "005930", BigDecimal.TEN, orderType,
				LocalDate.parse("2026-09-10"),
				new OcoConditionalOrderSubmissionRequest.Condition(
						firstSide, new BigDecimal("80000"), new BigDecimal("79000")),
				new OcoConditionalOrderSubmissionRequest.Condition(
						secondSide, new BigDecimal("65000"), new BigDecimal("64900")),
				false);
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
	 * 조건 주문 JSON 조각들을 토스증권 페이지 응답 봉투로 묶습니다.
	 *
	 * @param orders 쉼표로 연결할 조건 주문 JSON 조각
	 * @param nextCursor 다음 페이지 커서
	 * @param hasNext 다음 페이지 존재 여부
	 * @return 가짜 조건 주문 목록 응답 JSON
	 */
	private String 목록_응답_JSON(List<String> orders, String nextCursor, boolean hasNext) {
		String cursorJson = nextCursor == null ? "null" : "\"" + nextCursor + "\"";
		return """
				{
				  "result": {
				    "conditionalOrders": [%s],
				    "nextCursor": %s,
				    "hasNext": %s
				  }
				}
				""".formatted(String.join(",", orders), cursorJson, hasNext);
	}

	/**
	 * 조건 주문 JSON 한 건을 토스증권 상세 응답 봉투로 묶습니다.
	 *
	 * @param order 조건 주문 JSON 조각
	 * @return 가짜 조건 주문 상세 응답 JSON
	 */
	private String 상세_응답_JSON(String order) {
		return """
				{
				  "result": %s
				}
				""".formatted(order);
	}

	/**
	 * 식별값·종목·상태를 바꿀 수 있는 지정가 OCO 조건 주문 JSON을 만듭니다.
	 *
	 * @param id 조건 주문 식별값
	 * @param symbol 종목 코드
	 * @param status 조건 주문 전체 상태
	 * @return 완전한 OCO 조건 주문 JSON
	 */
	private String OCO_조건_주문_JSON(String id, String symbol, String status) {
		return """
				{
				  "conditionalOrderId": "%s",
				  "type": "OCO",
				  "status": "%s",
				  "symbol": "%s",
				  "market": "US",
				  "quantity": "10",
				  "orderType": "LIMIT",
				  "expireDate": "2026-09-10",
				  "first": {
				    "type": "STOP",
				    "status": "WATCHING",
				    "triggerPrice": "210",
				    "targetProfitRate": null,
				    "orderPrice": "211",
				    "triggeredOrderId": null
				  },
				  "second": {
				    "type": "STOP",
				    "status": "WATCHING",
				    "triggerPrice": "190",
				    "targetProfitRate": null,
				    "orderPrice": "189",
				    "triggeredOrderId": null
				  },
				  "createdAt": "2026-09-07T09:00:00+09:00"
				}
				""".formatted(id, status, symbol);
	}

	/**
	 * 발동된 일반 주문 식별값이 있는 단일 시장가 조건 주문 JSON을 만듭니다.
	 *
	 * @param id 조건 주문 식별값
	 * @return 완전한 단일 시장가 조건 주문 JSON
	 */
	private String SINGLE_시장가_조건_주문_JSON(String id) {
		return """
				{
				  "conditionalOrderId": "%s",
				  "type": "SINGLE",
				  "status": "ORDERED",
				  "symbol": "005930",
				  "market": "KR",
				  "quantity": "3",
				  "orderType": "MARKET",
				  "expireDate": "2026-09-10",
				  "first": {
				    "type": "STOP",
				    "status": "ORDERED",
				    "triggerPrice": "70000",
				    "targetProfitRate": null,
				    "orderPrice": null,
				    "triggeredOrderId": "triggered_order_001"
				  },
				  "second": null,
				  "createdAt": "2026-09-07T09:00:00+09:00"
				}
				""".formatted(id);
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
