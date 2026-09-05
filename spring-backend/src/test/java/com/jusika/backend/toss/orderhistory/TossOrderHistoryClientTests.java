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

import com.jusika.backend.orderhistory.OrderDetailResponse;
import com.jusika.backend.orderhistory.OrderHistoryNotFoundException;
import com.jusika.backend.orderhistory.OrderHistoryRequestException;
import com.jusika.backend.orderhistory.OrderHistoryServiceException;
import com.jusika.backend.orderhistory.OrderStatus;
import com.jusika.backend.orderpreview.OrderSide;
import com.jusika.backend.toss.TossApiProperties;
import com.jusika.backend.toss.auth.TossAccessTokenProvider;
import com.jusika.backend.toss.auth.TossAuthClient;
import com.jusika.backend.toss.orderhistory.TossOrderHistoryApiResponse.TossExecution;
import com.jusika.backend.toss.orderhistory.TossOrderHistoryApiResponse.TossOrderResult;

/**
 * 실제 주문 작업 없이 가짜 HTTP 서버로 토스증권 주문 상세 조회와 응답 변환을 검사합니다.
 */
class TossOrderHistoryClientTests {

	private static final String BASE_URL = "https://toss.example";
	private static final String ACCESS_TOKEN = "노출되면-안되는-테스트-토큰";
	private static final String ORDER_ID = "order_detail_test_001";
	private static final long ACCOUNT_SEQ = 1L;

	private MockRestServiceServer server;
	private TossOrderHistoryClient historyClient;

	/**
	 * 각 테스트에서 실제 네트워크 대신 사용할 가짜 토스증권 서버를 준비합니다.
	 */
	@BeforeEach
	void 각_테스트에_필요한_가짜_주문_상세_서버를_준비한다() {
		RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
		server = MockRestServiceServer.bindTo(builder).build();
		RestClient restClient = builder.build();
		TossAuthClient authClient = new TossAuthClient(restClient, 테스트_인증정보를_만든다());
		Clock clock = Clock.fixed(Instant.parse("2026-09-06T00:00:00Z"), ZoneOffset.UTC);
		TossAccessTokenProvider tokenProvider = new TossAccessTokenProvider(authClient, clock);
		historyClient = new TossOrderHistoryClient(restClient, tokenProvider);
	}

	/**
	 * 체결 완료 주문의 계좌·인증 헤더와 모든 숫자·시각·날짜를 정확히 변환하는지 검사합니다.
	 */
	@Test
	@DisplayName("체결 완료 주문 상세를 토스증권 명세에 맞게 조회한다")
	void 체결_완료_주문_상세를_토스증권_명세에_맞게_조회한다() {
		정상_토큰_발급_응답을_준비한다();
		server.expect(requestTo(BASE_URL + "/api/v1/orders/" + ORDER_ID))
				.andExpect(method(HttpMethod.GET))
				.andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
				.andExpect(header("X-Tossinvest-Account", Long.toString(ACCOUNT_SEQ)))
				.andRespond(withSuccess(주문_응답_JSON("FILLED", "10", "10"), MediaType.APPLICATION_JSON));

		OrderDetailResponse response = historyClient.getOrder(ACCOUNT_SEQ, ORDER_ID);

		assertThat(response.accountSeq()).isEqualTo(ACCOUNT_SEQ);
		assertThat(response.orderId()).isEqualTo(ORDER_ID);
		assertThat(response.symbol()).isEqualTo("005930");
		assertThat(response.side()).isEqualTo(OrderSide.BUY);
		assertThat(response.orderTypeCode()).isEqualTo("LIMIT");
		assertThat(response.timeInForceCode()).isEqualTo("DAY");
		assertThat(response.status()).isEqualTo(OrderStatus.FILLED);
		assertThat(response.brokerStatusCode()).isEqualTo("FILLED");
		assertThat(response.price()).isEqualByComparingTo("70000");
		assertThat(response.quantity()).isEqualByComparingTo("10");
		assertThat(response.orderAmount()).isNull();
		assertThat(response.currency()).isEqualTo("KRW");
		assertThat(response.orderedAt()).hasToString("2026-03-28T09:30+09:00");
		assertThat(response.canceledAt()).isNull();
		assertThat(response.execution().filledQuantity()).isEqualByComparingTo("10");
		assertThat(response.execution().averageFilledPrice()).isEqualByComparingTo("70000");
		assertThat(response.execution().filledAmount()).isEqualByComparingTo("700000");
		assertThat(response.execution().commission()).isEqualByComparingTo("1400");
		assertThat(response.execution().tax()).isEqualByComparingTo("0");
		assertThat(response.execution().filledAt()).hasToString("2026-03-28T09:31:15+09:00");
		assertThat(response.execution().settlementDate()).hasToString("2026-03-30");
		server.verify();
	}

	/**
	 * 미체결 주문의 선택 체결값이 null이고 체결 수량만 0인 응답을 허용하는지 검사합니다.
	 */
	@Test
	@DisplayName("미체결 주문의 빈 체결 결과를 안전하게 변환한다")
	void 미체결_주문의_빈_체결_결과를_안전하게_변환한다() {
		정상_토큰_발급_응답을_준비한다();
		server.expect(requestTo(BASE_URL + "/api/v1/orders/" + ORDER_ID))
				.andRespond(withSuccess(미체결_주문_응답_JSON(),
						MediaType.APPLICATION_JSON));

		OrderDetailResponse response = historyClient.getOrder(ACCOUNT_SEQ, ORDER_ID);

		assertThat(response.status()).isEqualTo(OrderStatus.PENDING);
		assertThat(response.execution().filledQuantity()).isEqualByComparingTo("0");
		assertThat(response.execution().averageFilledPrice()).isNull();
		assertThat(response.execution().filledAmount()).isNull();
		assertThat(response.execution().filledAt()).isNull();
		assertThat(response.execution().settlementDate()).isNull();
		server.verify();
	}

	/**
	 * 공식 명세에 아직 없는 새 주문 상태 코드를 버리지 않고 UNKNOWN과 원문으로 함께 반환하는지 검사합니다.
	 */
	@Test
	@DisplayName("새로운 토스증권 주문 상태 코드를 UNKNOWN으로 안전하게 보존한다")
	void 새로운_토스증권_주문_상태_코드를_UNKNOWN으로_안전하게_보존한다() {
		정상_토큰_발급_응답을_준비한다();
		server.expect(requestTo(BASE_URL + "/api/v1/orders/" + ORDER_ID))
				.andRespond(withSuccess(
						주문_응답_JSON("NEW_BROKER_STATUS", "10", "3"),
						MediaType.APPLICATION_JSON));

		OrderDetailResponse response = historyClient.getOrder(ACCOUNT_SEQ, ORDER_ID);

		assertThat(response.status()).isEqualTo(OrderStatus.UNKNOWN);
		assertThat(response.brokerStatusCode()).isEqualTo("NEW_BROKER_STATUS");
		server.verify();
	}

	/**
	 * 지정한 계좌에서 주문을 찾지 못한 HTTP 404를 사용자에게 안전한 찾을 수 없음으로 변환하는지 검사합니다.
	 */
	@Test
	@DisplayName("HTTP 404 주문 상세 응답을 찾을 수 없음으로 처리한다")
	void HTTP_404_주문_상세_응답을_찾을_수_없음으로_처리한다() {
		정상_토큰_발급_응답을_준비한다();
		server.expect(requestTo(BASE_URL + "/api/v1/orders/" + ORDER_ID))
				.andRespond(withStatus(HttpStatus.NOT_FOUND));

		assertThatThrownBy(() -> historyClient.getOrder(ACCOUNT_SEQ, ORDER_ID))
				.isInstanceOf(OrderHistoryNotFoundException.class)
				.hasMessage("지정한 계좌에서 주문을 찾을 수 없습니다.");
		server.verify();
	}

	/**
	 * 외부 서버 오류 응답 본문의 금융값은 노출하지 않고 HTTP 상태만 전달하는지 검사합니다.
	 */
	@Test
	@DisplayName("토스증권 서버 오류에서 응답 본문의 금융정보를 숨긴다")
	void 토스증권_서버_오류에서_응답_본문의_금융정보를_숨긴다() {
		정상_토큰_발급_응답을_준비한다();
		server.expect(requestTo(BASE_URL + "/api/v1/orders/" + ORDER_ID))
				.andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
						.contentType(MediaType.APPLICATION_JSON)
						.body("{\"privateAmount\":\"987654321\"}"));

		assertThatThrownBy(() -> historyClient.getOrder(ACCOUNT_SEQ, ORDER_ID))
				.isInstanceOf(OrderHistoryServiceException.class)
				.hasMessage("토스증권 주문 상세 조회에 실패했습니다. HTTP 상태: 500")
				.hasMessageNotContaining("987654321")
				.hasMessageNotContaining(ACCESS_TOKEN)
				.hasMessageNotContaining(ORDER_ID);
		server.verify();
	}

	/**
	 * 체결 수량이 전체 주문 수량보다 크면 잘못된 외부 응답으로 거절하는지 검사합니다.
	 */
	@Test
	@DisplayName("주문 수량을 초과한 체결 수량 응답을 거절한다")
	void 주문_수량을_초과한_체결_수량_응답을_거절한다() {
		정상_토큰_발급_응답을_준비한다();
		server.expect(requestTo(BASE_URL + "/api/v1/orders/" + ORDER_ID))
				.andRespond(withSuccess(
						주문_응답_JSON("FILLED", "10", "11"),
						MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> historyClient.getOrder(ACCOUNT_SEQ, ORDER_ID))
				.isInstanceOf(OrderHistoryServiceException.class)
				.hasMessage("토스증권 주문 상세 응답 형식이 올바르지 않습니다.");
		server.verify();
	}

	/**
	 * 공식 십진수 형식이 아닌 지수 표기 수량을 숫자로 임의 해석하지 않는지 검사합니다.
	 */
	@Test
	@DisplayName("지수 표기 주문 수량 응답을 거절한다")
	void 지수_표기_주문_수량_응답을_거절한다() {
		정상_토큰_발급_응답을_준비한다();
		server.expect(requestTo(BASE_URL + "/api/v1/orders/" + ORDER_ID))
				.andRespond(withSuccess(
						주문_응답_JSON("FILLED", "1E+1", "10"),
						MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> historyClient.getOrder(ACCOUNT_SEQ, ORDER_ID))
				.isInstanceOf(OrderHistoryServiceException.class)
				.hasMessage("토스증권 주문 상세 응답 형식이 올바르지 않습니다.");
		server.verify();
	}

	/**
	 * 요청한 주문 식별값과 응답의 식별값이 다르면 다른 주문 정보로 오인하지 않는지 검사합니다.
	 */
	@Test
	@DisplayName("요청과 다른 주문 식별값을 반환한 응답을 거절한다")
	void 요청과_다른_주문_식별값을_반환한_응답을_거절한다() {
		정상_토큰_발급_응답을_준비한다();
		server.expect(requestTo(BASE_URL + "/api/v1/orders/" + ORDER_ID))
				.andRespond(withSuccess(
						주문_응답_JSON("FILLED", "10", "10").replace(ORDER_ID, "different-order"),
						MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> historyClient.getOrder(ACCOUNT_SEQ, ORDER_ID))
				.isInstanceOf(OrderHistoryServiceException.class)
				.hasMessage("토스증권 주문 상세 응답 형식이 올바르지 않습니다.");
		server.verify();
	}

	/**
	 * 잘못된 계좌와 주문 식별값은 인증이나 토스증권 조회 전에 차단하는지 검사합니다.
	 */
	@Test
	@DisplayName("잘못된 주문 상세 조회 식별값을 외부 호출 전에 차단한다")
	void 잘못된_주문_상세_조회_식별값을_외부_호출_전에_차단한다() {
		assertThatThrownBy(() -> historyClient.getOrder(0, ORDER_ID))
				.isInstanceOf(OrderHistoryRequestException.class)
				.hasMessage("계좌 식별값은 1 이상이어야 합니다.");
		assertThatThrownBy(() -> historyClient.getOrder(ACCOUNT_SEQ, "\n"))
				.isInstanceOf(OrderHistoryRequestException.class)
				.hasMessage("주문 식별값 형식이 올바르지 않습니다.");
		server.verify();
	}

	/**
	 * 주문 상세와 토스 원본 객체의 문자열 표현에서 식별값과 모든 금융값을 숨기는지 검사합니다.
	 */
	@Test
	@DisplayName("주문 상세 객체의 문자열 표현에서 금융정보를 숨긴다")
	void 주문_상세_객체의_문자열_표현에서_금융정보를_숨긴다() {
		TossExecution rawExecution = new TossExecution(
				"987", "654321", "123456", "777", "888",
				"2026-03-28T09:31:15+09:00", "2026-03-30");
		TossOrderResult rawResult = new TossOrderResult(
				ORDER_ID, "005930", "BUY", "LIMIT", "DAY", "FILLED",
				"654321", "987", null, "KRW", "2026-03-28T09:30:00+09:00", null,
				rawExecution);
		TossOrderHistoryApiResponse rawResponse = new TossOrderHistoryApiResponse(rawResult);

		assertThat(rawExecution.toString())
				.doesNotContain("987", "654321", "123456", "777", "888");
		assertThat(rawResult.toString())
				.doesNotContain(ORDER_ID, "654321", "987", "123456", "777", "888");
		assertThat(rawResponse.toString())
				.doesNotContain(ORDER_ID, "654321", "987", "123456", "777", "888");
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
	 * 상태와 수량만 바꿀 수 있는 완전한 토스증권 주문 상세 JSON을 만듭니다.
	 *
	 * @param status 토스증권 주문 상태 코드
	 * @param quantity 전체 주문 수량
	 * @param filledQuantity 누적 체결 수량
	 * @return 가짜 주문 상세 응답 JSON
	 */
	private String 주문_응답_JSON(String status, String quantity, String filledQuantity) {
		return """
				{
				  "result": {
				    "orderId": "%s",
				    "symbol": "005930",
				    "side": "BUY",
				    "orderType": "LIMIT",
				    "timeInForce": "DAY",
				    "status": "%s",
				    "price": "70000",
				    "quantity": "%s",
				    "orderAmount": null,
				    "currency": "KRW",
				    "orderedAt": "2026-03-28T09:30:00+09:00",
				    "canceledAt": null,
				    "execution": {
				      "filledQuantity": "%s",
				      "averageFilledPrice": "70000",
				      "filledAmount": "700000",
				      "commission": "1400",
				      "tax": "0",
				      "filledAt": "2026-03-28T09:31:15+09:00",
				      "settlementDate": "2026-03-30"
				    }
				  }
				}
				""".formatted(ORDER_ID, status, quantity, filledQuantity);
	}

	/**
	 * 모든 선택 체결값이 null이고 체결 수량만 0인 미체결 주문 응답을 만듭니다.
	 *
	 * @return 가짜 미체결 주문 상세 응답 JSON
	 */
	private String 미체결_주문_응답_JSON() {
		return 주문_응답_JSON("PENDING", "10", "0")
				.replace("\"averageFilledPrice\": \"70000\"", "\"averageFilledPrice\": null")
				.replace("\"filledAmount\": \"700000\"", "\"filledAmount\": null")
				.replace("\"commission\": \"1400\"", "\"commission\": null")
				.replace("\"tax\": \"0\"", "\"tax\": null")
				.replace("\"filledAt\": \"2026-03-28T09:31:15+09:00\"", "\"filledAt\": null")
				.replace("\"settlementDate\": \"2026-03-30\"", "\"settlementDate\": null");
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
