package com.jusika.backend.toss.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.jusika.backend.order.AmountOrderSubmissionRequest;
import com.jusika.backend.order.OrderCreationResponse;
import com.jusika.backend.order.OrderTimeInForce;
import com.jusika.backend.order.QuantityOrderSubmissionRequest;
import com.jusika.backend.orderpreview.OrderSide;
import com.jusika.backend.orderpreview.OrderType;
import com.jusika.backend.toss.TossApiProperties;
import com.jusika.backend.toss.auth.TossAccessTokenProvider;
import com.jusika.backend.toss.auth.TossAuthClient;

/**
 * 실제 주문을 보내지 않고 가짜 HTTP 서버로 토스증권 주문 생성 클라이언트를 검사합니다.
 */
class TossOrderClientTests {

	private static final String BASE_URL = "https://toss.example";
	private static final String ACCESS_TOKEN = "노출되면-안되는-테스트-토큰";
	private static final String CLIENT_ORDER_ID = "order-20260904-001";
	private static final String ORDER_ID = "노출되면-안되는-주문-식별값";
	private static final long ACCOUNT_SEQ = 1L;

	private MockRestServiceServer server;
	private TossOrderClient orderClient;

	/**
	 * 각 테스트에서 사용할 가짜 토스증권 서버와 주문 클라이언트를 준비합니다.
	 */
	@BeforeEach
	void 각_테스트에_필요한_가짜_주문_서버를_준비한다() {
		RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
		server = MockRestServiceServer.bindTo(builder).build();
		RestClient restClient = builder.build();
		TossAuthClient authClient = new TossAuthClient(restClient, 테스트_인증정보를_만든다());
		Clock clock = Clock.fixed(Instant.parse("2026-09-04T00:00:00Z"), ZoneOffset.UTC);
		TossAccessTokenProvider tokenProvider = new TossAccessTokenProvider(authClient, clock);
		orderClient = new TossOrderClient(restClient, tokenProvider);
	}

	/**
	 * 국내 지정가 매수의 계좌·인증 헤더와 문자열 수량·가격을 정확히 전송하는지 검사합니다.
	 */
	@Test
	@DisplayName("국내 지정가 매수 주문 형식을 토스증권 명세에 맞게 전송한다")
	void 국내_지정가_매수_주문_형식을_토스증권_명세에_맞게_전송한다() {
		정상_토큰_발급_응답을_준비한다();
		server.expect(requestTo(BASE_URL + "/api/v1/orders"))
				.andExpect(method(HttpMethod.POST))
				.andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
				.andExpect(header("X-Tossinvest-Account", Long.toString(ACCOUNT_SEQ)))
				.andExpect(jsonPath("$.clientOrderId").value(CLIENT_ORDER_ID))
				.andExpect(jsonPath("$.symbol").value("005930"))
				.andExpect(jsonPath("$.side").value("BUY"))
				.andExpect(jsonPath("$.orderType").value("LIMIT"))
				.andExpect(jsonPath("$.timeInForce").value("DAY"))
				.andExpect(jsonPath("$.quantity").value("10"))
				.andExpect(jsonPath("$.price").value("70000"))
				.andExpect(jsonPath("$.confirmHighValueOrder").value(false))
				.andRespond(정상_주문_응답());

		OrderCreationResponse response = orderClient.createQuantityOrder(
				ACCOUNT_SEQ,
				new QuantityOrderSubmissionRequest(
						CLIENT_ORDER_ID,
						"005930",
						OrderSide.BUY,
						OrderType.LIMIT,
						null,
						new BigDecimal("10"),
						new BigDecimal("70000"),
						false));

		assertThat(response.orderId()).isEqualTo(ORDER_ID);
		assertThat(response.clientOrderId()).isEqualTo(CLIENT_ORDER_ID);
		server.verify();
	}

	/**
	 * 미국 시장가 소수 수량 매도에서 가격을 보내지 않고 소수 자릿수를 유지하는지 검사합니다.
	 */
	@Test
	@DisplayName("미국 시장가 소수 수량 매도 주문에서 가격을 제외한다")
	void 미국_시장가_소수_수량_매도_주문에서_가격을_제외한다() {
		정상_토큰_발급_응답을_준비한다();
		server.expect(requestTo(BASE_URL + "/api/v1/orders"))
				.andExpect(method(HttpMethod.POST))
				.andExpect(jsonPath("$.symbol").value("AAPL"))
				.andExpect(jsonPath("$.side").value("SELL"))
				.andExpect(jsonPath("$.orderType").value("MARKET"))
				.andExpect(jsonPath("$.quantity").value("0.123456"))
				.andExpect(jsonPath("$.price").doesNotExist())
				.andRespond(정상_주문_응답());

		orderClient.createQuantityOrder(
				ACCOUNT_SEQ,
				new QuantityOrderSubmissionRequest(
						CLIENT_ORDER_ID,
						"aapl",
						OrderSide.SELL,
						OrderType.MARKET,
						OrderTimeInForce.DAY,
						new BigDecimal("0.123456"),
						null,
						false));

		server.verify();
	}

	/**
	 * 미국 주식 달러 금액 기반 주문은 시장가와 orderAmount 필드만 사용하는지 검사합니다.
	 */
	@Test
	@DisplayName("미국 주식 달러 금액 기반 시장가 주문을 전송한다")
	void 미국_주식_달러_금액_기반_시장가_주문을_전송한다() {
		정상_토큰_발급_응답을_준비한다();
		server.expect(requestTo(BASE_URL + "/api/v1/orders"))
				.andExpect(method(HttpMethod.POST))
				.andExpect(jsonPath("$.clientOrderId").value(CLIENT_ORDER_ID))
				.andExpect(jsonPath("$.symbol").value("AAPL"))
				.andExpect(jsonPath("$.side").value("BUY"))
				.andExpect(jsonPath("$.orderType").value("MARKET"))
				.andExpect(jsonPath("$.orderAmount").value("100.5"))
				.andExpect(jsonPath("$.quantity").doesNotExist())
				.andExpect(jsonPath("$.price").doesNotExist())
				.andRespond(정상_주문_응답());

		OrderCreationResponse response = orderClient.createAmountOrder(
				ACCOUNT_SEQ,
				new AmountOrderSubmissionRequest(
						CLIENT_ORDER_ID,
						"aapl",
						OrderSide.BUY,
						new BigDecimal("100.5"),
						false));

		assertThat(response.clientOrderId()).isEqualTo(CLIENT_ORDER_ID);
		server.verify();
	}

	/**
	 * 미국 장 마감 지정가 주문의 CLS 유효 조건을 보존하는지 검사합니다.
	 */
	@Test
	@DisplayName("미국 장 마감 지정가 주문의 CLS 조건을 전송한다")
	void 미국_장_마감_지정가_주문의_CLS_조건을_전송한다() {
		정상_토큰_발급_응답을_준비한다();
		server.expect(requestTo(BASE_URL + "/api/v1/orders"))
				.andExpect(jsonPath("$.timeInForce").value("CLS"))
				.andExpect(jsonPath("$.orderType").value("LIMIT"))
				.andExpect(jsonPath("$.price").value("185.5"))
				.andExpect(jsonPath("$.confirmHighValueOrder").value(true))
				.andRespond(정상_주문_응답());

		orderClient.createQuantityOrder(
				ACCOUNT_SEQ,
				new QuantityOrderSubmissionRequest(
						CLIENT_ORDER_ID,
						"AAPL",
						OrderSide.BUY,
						OrderType.LIMIT,
						OrderTimeInForce.CLS,
						BigDecimal.ONE,
						new BigDecimal("185.5"),
						true));

		server.verify();
	}

	/**
	 * 중복 방지 식별값이 없거나 명세 형식과 다르면 주문 전송 전에 차단하는지 검사합니다.
	 */
	@Test
	@DisplayName("잘못된 주문 멱등성 식별값은 주문 전송 전에 차단한다")
	void 잘못된_주문_멱등성_식별값은_주문_전송_전에_차단한다() {
		QuantityOrderSubmissionRequest request = new QuantityOrderSubmissionRequest(
				"허용되지 않는 식별값",
				"005930",
				OrderSide.BUY,
				OrderType.MARKET,
				OrderTimeInForce.DAY,
				BigDecimal.ONE,
				null,
				false);

		assertThatThrownBy(() -> orderClient.createQuantityOrder(ACCOUNT_SEQ, request))
				.isInstanceOf(TossOrderException.class)
				.hasMessage("주문 멱등성 식별값은 36자 이하의 영문, 숫자, 하이픈과 밑줄만 사용할 수 있습니다.");
		server.verify();
	}

	/**
	 * 시장가 주문에 가격을 넣으면 토스증권에 전송하기 전에 거절하는지 검사합니다.
	 */
	@Test
	@DisplayName("시장가 주문에 입력한 가격은 전송 전에 거절한다")
	void 시장가_주문에_입력한_가격은_전송_전에_거절한다() {
		QuantityOrderSubmissionRequest request = new QuantityOrderSubmissionRequest(
				CLIENT_ORDER_ID,
				"005930",
				OrderSide.BUY,
				OrderType.MARKET,
				OrderTimeInForce.DAY,
				BigDecimal.ONE,
				new BigDecimal("70000"),
				false);

		assertThatThrownBy(() -> orderClient.createQuantityOrder(ACCOUNT_SEQ, request))
				.isInstanceOf(TossOrderException.class)
				.hasMessage("시장가 주문에는 가격을 입력할 수 없습니다.");
		server.verify();
	}

	/**
	 * 지정가 주문에 가격이 없으면 토스증권에 전송하기 전에 거절하는지 검사합니다.
	 */
	@Test
	@DisplayName("가격이 없는 지정가 주문은 전송 전에 거절한다")
	void 가격이_없는_지정가_주문은_전송_전에_거절한다() {
		QuantityOrderSubmissionRequest request = new QuantityOrderSubmissionRequest(
				CLIENT_ORDER_ID,
				"005930",
				OrderSide.BUY,
				OrderType.LIMIT,
				OrderTimeInForce.DAY,
				BigDecimal.ONE,
				null,
				false);

		assertThatThrownBy(() -> orderClient.createQuantityOrder(ACCOUNT_SEQ, request))
				.isInstanceOf(TossOrderException.class)
				.hasMessage("지정가 주문에는 0보다 큰 가격이 필요합니다.");
		server.verify();
	}

	/**
	 * 소수 수량 매수와 소수점 6자리를 초과한 매도를 모두 전송 전에 차단하는지 검사합니다.
	 */
	@Test
	@DisplayName("허용되지 않는 소수 수량 주문은 전송 전에 거절한다")
	void 허용되지_않는_소수_수량_주문은_전송_전에_거절한다() {
		QuantityOrderSubmissionRequest fractionalBuy = new QuantityOrderSubmissionRequest(
				CLIENT_ORDER_ID,
				"AAPL",
				OrderSide.BUY,
				OrderType.MARKET,
				OrderTimeInForce.DAY,
				new BigDecimal("0.5"),
				null,
				false);
		QuantityOrderSubmissionRequest excessiveScale = new QuantityOrderSubmissionRequest(
				CLIENT_ORDER_ID,
				"AAPL",
				OrderSide.SELL,
				OrderType.MARKET,
				OrderTimeInForce.DAY,
				new BigDecimal("0.1234567"),
				null,
				false);

		assertThatThrownBy(() -> orderClient.createQuantityOrder(ACCOUNT_SEQ, fractionalBuy))
				.isInstanceOf(TossOrderException.class)
				.hasMessage("소수점 수량은 미국 주식 시장가 매도에만 사용할 수 있습니다.");
		assertThatThrownBy(() -> orderClient.createQuantityOrder(ACCOUNT_SEQ, excessiveScale))
				.isInstanceOf(TossOrderException.class)
				.hasMessage("미국 주식 소수점 수량은 소수점 6자리까지 사용할 수 있습니다.");
		server.verify();
	}

	/**
	 * 금액 기반 주문에 국내 숫자 종목 코드가 들어오면 미국 전용 규칙으로 차단하는지 검사합니다.
	 */
	@Test
	@DisplayName("금액 기반 주문은 미국 주식 종목 코드만 허용한다")
	void 금액_기반_주문은_미국_주식_종목_코드만_허용한다() {
		AmountOrderSubmissionRequest request = new AmountOrderSubmissionRequest(
				CLIENT_ORDER_ID,
				"005930",
				OrderSide.BUY,
				new BigDecimal("100"),
				false);

		assertThatThrownBy(() -> orderClient.createAmountOrder(ACCOUNT_SEQ, request))
				.isInstanceOf(TossOrderException.class)
				.hasMessage("금액 주문에는 올바른 미국 주식 종목 코드가 필요합니다.");
		server.verify();
	}

	/**
	 * 토스증권이 요청과 다른 멱등성 식별값을 반환하면 주문 접수 여부를 불명으로 표시하는지 검사합니다.
	 */
	@Test
	@DisplayName("응답의 주문 멱등성 식별값이 다르면 결과 불명으로 처리한다")
	void 응답의_주문_멱등성_식별값이_다르면_결과_불명으로_처리한다() {
		정상_토큰_발급_응답을_준비한다();
		server.expect(requestTo(BASE_URL + "/api/v1/orders"))
				.andRespond(withSuccess("""
						{"result":{"orderId":"%s","clientOrderId":"different-order-id"}}
						""".formatted(ORDER_ID), MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> orderClient.createQuantityOrder(
				ACCOUNT_SEQ, 정상_시장가_수량_주문을_만든다()))
				.isInstanceOfSatisfying(TossOrderException.class, exception -> {
					assertThat(exception.getMessage())
							.isEqualTo("토스증권이 요청과 다른 주문 멱등성 식별값을 반환했습니다.");
					assertThat(exception.isSubmissionStateUnknown()).isTrue();
				});
		server.verify();
	}

	/**
	 * 성공 응답에 주문 식별값이 없으면 주문이 생성됐을 가능성을 버리지 않는지 검사합니다.
	 */
	@Test
	@DisplayName("불완전한 성공 응답은 주문 접수 여부 불명으로 처리한다")
	void 불완전한_성공_응답은_주문_접수_여부_불명으로_처리한다() {
		정상_토큰_발급_응답을_준비한다();
		server.expect(requestTo(BASE_URL + "/api/v1/orders"))
				.andRespond(withSuccess("""
						{"result":{}}
						""", MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> orderClient.createQuantityOrder(
				ACCOUNT_SEQ, 정상_시장가_수량_주문을_만든다()))
				.isInstanceOfSatisfying(TossOrderException.class, exception -> {
					assertThat(exception.getMessage())
							.isEqualTo("토스증권 주문 생성 응답 형식이 올바르지 않습니다.");
					assertThat(exception.isSubmissionStateUnknown()).isTrue();
				});
		server.verify();
	}

	/**
	 * 업무 규칙 거절인 HTTP 422는 주문 접수 실패가 확정된 상태로 안전하게 변환하는지 검사합니다.
	 */
	@Test
	@DisplayName("HTTP 422 주문 거절은 접수 실패가 확정된 상태로 처리한다")
	void HTTP_422_주문_거절은_접수_실패가_확정된_상태로_처리한다() {
		정상_토큰_발급_응답을_준비한다();
		server.expect(requestTo(BASE_URL + "/api/v1/orders"))
				.andRespond(withStatus(HttpStatus.UNPROCESSABLE_CONTENT)
						.contentType(MediaType.APPLICATION_JSON)
						.body("{\"privateAmount\":\"987654321\"}"));

		assertThatThrownBy(() -> orderClient.createQuantityOrder(
				ACCOUNT_SEQ, 정상_시장가_수량_주문을_만든다()))
				.isInstanceOfSatisfying(TossOrderException.class, exception -> {
					assertThat(exception.getMessage())
							.isEqualTo("토스증권 주문 생성에 실패했습니다. HTTP 상태: 422")
							.doesNotContain("987654321", ACCESS_TOKEN);
					assertThat(exception.getHttpStatus()).isEqualTo(422);
					assertThat(exception.isSubmissionStateUnknown()).isFalse();
				});
		server.verify();
	}

	/**
	 * HTTP 500은 토스증권 내부에서 주문이 처리됐을 가능성이 있어 결과 불명으로 표시하는지 검사합니다.
	 */
	@Test
	@DisplayName("HTTP 500 주문 오류는 접수 여부 불명으로 처리한다")
	void HTTP_500_주문_오류는_접수_여부_불명으로_처리한다() {
		정상_토큰_발급_응답을_준비한다();
		server.expect(requestTo(BASE_URL + "/api/v1/orders"))
				.andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

		assertThatThrownBy(() -> orderClient.createQuantityOrder(
				ACCOUNT_SEQ, 정상_시장가_수량_주문을_만든다()))
				.isInstanceOfSatisfying(TossOrderException.class, exception -> {
					assertThat(exception.getHttpStatus()).isEqualTo(500);
					assertThat(exception.isSubmissionStateUnknown()).isTrue();
				});
		server.verify();
	}

	/**
	 * 요청 전송 중 연결이 끊기면 같은 멱등성 키로 확인해야 하는 결과 불명으로 처리하는지 검사합니다.
	 */
	@Test
	@DisplayName("주문 전송 중 네트워크 오류는 접수 여부 불명으로 처리한다")
	void 주문_전송_중_네트워크_오류는_접수_여부_불명으로_처리한다() {
		정상_토큰_발급_응답을_준비한다();
		server.expect(requestTo(BASE_URL + "/api/v1/orders"))
				.andRespond(request -> {
					throw new IOException("테스트용 연결 끊김");
				});

		assertThatThrownBy(() -> orderClient.createQuantityOrder(
				ACCOUNT_SEQ, 정상_시장가_수량_주문을_만든다()))
				.isInstanceOfSatisfying(TossOrderException.class, exception -> {
					assertThat(exception.getMessage())
							.isEqualTo("토스증권 주문 생성 결과를 확인하지 못했습니다.")
							.doesNotContain("테스트용 연결 끊김");
					assertThat(exception.getHttpStatus()).isNull();
					assertThat(exception.isSubmissionStateUnknown()).isTrue();
				});
		server.verify();
	}

	/**
	 * 인증 실패는 실제 주문 전송 전에 발생하므로 주문 접수 여부가 불확실하지 않은지 검사합니다.
	 */
	@Test
	@DisplayName("인증 토큰 실패는 주문 전송 전 실패로 처리한다")
	void 인증_토큰_실패는_주문_전송_전_실패로_처리한다() {
		server.expect(requestTo(BASE_URL + "/oauth2/token"))
				.andRespond(withStatus(HttpStatus.UNAUTHORIZED));

		assertThatThrownBy(() -> orderClient.createQuantityOrder(
				ACCOUNT_SEQ, 정상_시장가_수량_주문을_만든다()))
				.isInstanceOfSatisfying(TossOrderException.class, exception -> {
					assertThat(exception.getMessage())
							.isEqualTo("토스증권 주문 전에 인증 토큰을 준비하지 못했습니다.");
					assertThat(exception.isSubmissionStateUnknown()).isFalse();
				});
		server.verify();
	}

	/**
	 * 주문 요청과 응답의 문자열 표현에서 주문 금액과 식별값을 숨기는지 검사합니다.
	 */
	@Test
	@DisplayName("주문 객체의 문자열 표현에서 금융정보와 주문 식별값을 숨긴다")
	void 주문_객체의_문자열_표현에서_금융정보와_주문_식별값을_숨긴다() {
		QuantityOrderSubmissionRequest quantityRequest = new QuantityOrderSubmissionRequest(
				CLIENT_ORDER_ID, "005930", OrderSide.BUY, OrderType.LIMIT,
				OrderTimeInForce.DAY, new BigDecimal("987"), new BigDecimal("654321"), false);
		AmountOrderSubmissionRequest amountRequest = new AmountOrderSubmissionRequest(
				CLIENT_ORDER_ID, "AAPL", OrderSide.BUY, new BigDecimal("12345.67"), false);
		OrderCreationResponse response = new OrderCreationResponse(ORDER_ID, CLIENT_ORDER_ID);
		TossOrderApiResponse rawResponse = new TossOrderApiResponse(
				new TossOrderApiResponse.TossOrderResult(ORDER_ID, CLIENT_ORDER_ID));

		assertThat(quantityRequest.toString())
				.doesNotContain(CLIENT_ORDER_ID, "987", "654321");
		assertThat(amountRequest.toString())
				.doesNotContain(CLIENT_ORDER_ID, "12345.67");
		assertThat(response.toString()).doesNotContain(ORDER_ID, CLIENT_ORDER_ID);
		assertThat(rawResponse.toString()).doesNotContain(ORDER_ID, CLIENT_ORDER_ID);
		assertThat(rawResponse.result().toString()).doesNotContain(ORDER_ID, CLIENT_ORDER_ID);
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
	 * 가짜 주문 서버가 요청의 멱등성 식별값을 그대로 반환하는 성공 응답을 만듭니다.
	 *
	 * @return 가짜 주문 식별값을 담은 성공 응답 생성기
	 */
	private org.springframework.test.web.client.ResponseCreator 정상_주문_응답() {
		return withSuccess("""
				{"result":{"orderId":"%s","clientOrderId":"%s"}}
				""".formatted(ORDER_ID, CLIENT_ORDER_ID), MediaType.APPLICATION_JSON);
	}

	/**
	 * 오류 처리 테스트에 공통으로 사용할 정상적인 정수 시장가 매수 요청을 만듭니다.
	 *
	 * @return 가짜 서버에 전송 가능한 시장가 수량 주문
	 */
	private QuantityOrderSubmissionRequest 정상_시장가_수량_주문을_만든다() {
		return new QuantityOrderSubmissionRequest(
				CLIENT_ORDER_ID,
				"005930",
				OrderSide.BUY,
				OrderType.MARKET,
				OrderTimeInForce.DAY,
				BigDecimal.ONE,
				null,
				false);
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
