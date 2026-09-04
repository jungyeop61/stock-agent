package com.jusika.backend.toss.orderinfo;

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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.jusika.backend.commission.CommissionsResponse;
import com.jusika.backend.commission.CommissionsResponse.CommissionItem;
import com.jusika.backend.toss.TossApiProperties;
import com.jusika.backend.toss.auth.TossAccessTokenProvider;
import com.jusika.backend.toss.auth.TossAuthClient;
import com.jusika.backend.toss.orderinfo.TossCommissionsApiResponse.TossCommissionItem;

/**
 * 실제 토스증권 서버 대신 가짜 HTTP 서버로 국내와 미국 시장의 매매 수수료 조회를 검사합니다.
 */
class TossCommissionsClientTests {

	private static final String BASE_URL = "https://toss.example";
	private static final String ACCESS_TOKEN = "노출되면-안되는-테스트-토큰";
	private static final long ACCOUNT_SEQ = 1L;

	private MockRestServiceServer server;
	private TossCommissionsClient commissionsClient;

	/**
	 * 각 테스트에서 사용할 가짜 토스증권 서버와 매매 수수료 클라이언트를 준비합니다.
	 */
	@BeforeEach
	void 각_테스트에_필요한_가짜_주문정보_서버를_준비한다() {
		RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
		server = MockRestServiceServer.bindTo(builder).build();
		RestClient restClient = builder.build();
		TossAuthClient authClient = new TossAuthClient(restClient, 테스트_인증정보를_만든다());
		Clock clock = Clock.fixed(Instant.parse("2026-09-04T00:00:00Z"), ZoneOffset.UTC);
		TossAccessTokenProvider tokenProvider = new TossAccessTokenProvider(authClient, clock);
		commissionsClient = new TossCommissionsClient(restClient, tokenProvider);
	}

	/**
	 * 계좌와 인증 헤더를 전달하고 국내와 미국 수수료의 숫자와 날짜를 변환하는지 확인합니다.
	 */
	@Test
	@DisplayName("국내와 미국 시장의 매매 수수료를 계좌 식별값으로 조회한다")
	void 국내와_미국_시장의_매매_수수료를_계좌_식별값으로_조회한다() {
		정상_토큰_발급_응답을_준비한다();
		정상_수수료_응답을_준비한다();

		CommissionsResponse response = commissionsClient.getCommissions(ACCOUNT_SEQ);

		assertThat(response.accountSeq()).isEqualTo(ACCOUNT_SEQ);
		assertThat(response.commissions()).hasSize(2);

		CommissionItem kr = response.commissions().getFirst();
		assertThat(kr.marketCountry()).isEqualTo("KR");
		assertThat(kr.commissionRate()).isEqualByComparingTo("0.00015");
		assertThat(kr.startDate()).isEqualTo(LocalDate.of(2026, 1, 1));
		assertThat(kr.endDate()).isEqualTo(LocalDate.of(2026, 12, 31));

		CommissionItem us = response.commissions().get(1);
		assertThat(us.marketCountry()).isEqualTo("US");
		assertThat(us.commissionRate()).isEqualByComparingTo("0.001");
		assertThat(us.startDate()).isNull();
		assertThat(us.endDate()).isNull();
		server.verify();
	}

	/**
	 * 0이나 음수인 계좌 식별값을 외부 요청 전에 차단하는지 확인합니다.
	 */
	@Test
	@DisplayName("잘못된 계좌 식별값은 토스증권 호출 전에 차단한다")
	void 잘못된_계좌_식별값은_토스증권_호출_전에_차단한다() {
		assertThatThrownBy(() -> commissionsClient.getCommissions(0))
				.isInstanceOf(TossOrderInfoException.class)
				.hasMessage("계좌 식별값은 1 이상이어야 합니다.");
		server.verify();
	}

	/**
	 * 비어 있는 시장별 수수료 목록을 안전한 응답 오류로 처리하는지 확인합니다.
	 */
	@Test
	@DisplayName("비어 있는 수수료 목록은 안전하게 거절한다")
	void 비어_있는_수수료_목록은_안전하게_거절한다() {
		정상_토큰_발급_응답을_준비한다();
		server.expect(requestTo(BASE_URL + "/api/v1/commissions"))
				.andRespond(withSuccess("{\"result\": []}", MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> commissionsClient.getCommissions(ACCOUNT_SEQ))
				.isInstanceOf(TossOrderInfoException.class)
				.hasMessage("토스증권 매매 수수료 응답 형식이 올바르지 않습니다.");
		server.verify();
	}

	/**
	 * 지원 범위 밖 시장 코드가 반환되면 잘못된 수수료 사용을 막는지 확인합니다.
	 */
	@Test
	@DisplayName("지원하지 않는 시장의 수수료는 안전하게 거절한다")
	void 지원하지_않는_시장의_수수료는_안전하게_거절한다() {
		정상_토큰_발급_응답을_준비한다();
		수수료_응답을_준비한다("JP", "0.001", null, null);

		assertThatThrownBy(() -> commissionsClient.getCommissions(ACCOUNT_SEQ))
				.isInstanceOf(TossOrderInfoException.class)
				.hasMessage("토스증권 매매 수수료 항목에 필수 값이 없습니다.");
		server.verify();
	}

	/**
	 * 숫자가 아닌 수수료율을 계산에 사용하지 않고 안전한 응답 오류로 처리하는지 확인합니다.
	 */
	@Test
	@DisplayName("숫자가 아닌 수수료율은 안전하게 거절한다")
	void 숫자가_아닌_수수료율은_안전하게_거절한다() {
		정상_토큰_발급_응답을_준비한다();
		수수료_응답을_준비한다("KR", "영점영일", "2026-01-01", null);

		assertThatThrownBy(() -> commissionsClient.getCommissions(ACCOUNT_SEQ))
				.isInstanceOf(TossOrderInfoException.class)
				.hasMessage("토스증권 매매 수수료의 숫자 또는 날짜 형식이 올바르지 않습니다.")
				.hasMessageNotContaining("영점영일");
		server.verify();
	}

	/**
	 * 금융 계산에 사용할 수 없는 음수 수수료율을 안전하게 거절하는지 확인합니다.
	 */
	@Test
	@DisplayName("음수인 수수료율은 안전하게 거절한다")
	void 음수인_수수료율은_안전하게_거절한다() {
		정상_토큰_발급_응답을_준비한다();
		수수료_응답을_준비한다("KR", "-0.01", "2026-01-01", null);

		assertThatThrownBy(() -> commissionsClient.getCommissions(ACCOUNT_SEQ))
				.isInstanceOf(TossOrderInfoException.class)
				.hasMessage("토스증권 매매 수수료율은 음수일 수 없습니다.");
		server.verify();
	}

	/**
	 * 날짜 형식이 잘못된 수수료 항목을 안전한 응답 오류로 처리하는지 확인합니다.
	 */
	@Test
	@DisplayName("잘못된 적용 날짜는 안전하게 거절한다")
	void 잘못된_적용_날짜는_안전하게_거절한다() {
		정상_토큰_발급_응답을_준비한다();
		수수료_응답을_준비한다("KR", "0.00015", "2026년1월1일", null);

		assertThatThrownBy(() -> commissionsClient.getCommissions(ACCOUNT_SEQ))
				.isInstanceOf(TossOrderInfoException.class)
				.hasMessage("토스증권 매매 수수료의 숫자 또는 날짜 형식이 올바르지 않습니다.")
				.hasMessageNotContaining("2026년1월1일");
		server.verify();
	}

	/**
	 * 토스 원본 응답 객체를 실수로 로그에 기록해도 실제 수수료율과 적용 기간이 보이지 않는지 확인합니다.
	 */
	@Test
	@DisplayName("원본 응답 객체의 문자열 표현에서 수수료 조건을 숨긴다")
	void 원본_응답_객체의_문자열_표현에서_수수료_조건을_숨긴다() {
		TossCommissionItem item = new TossCommissionItem("KR", "0.123456789", "2026-01-01", "2026-12-31");
		TossCommissionsApiResponse response = new TossCommissionsApiResponse(java.util.List.of(item));

		assertThat(item.toString())
				.doesNotContain("0.123456789")
				.doesNotContain("2026-01-01")
				.doesNotContain("2026-12-31");
		assertThat(response.toString()).doesNotContain("0.123456789");
	}

	/**
	 * 토스증권 오류가 액세스 토큰과 금융정보를 포함하지 않는 안전한 예외로 바뀌는지 확인합니다.
	 */
	@Test
	@DisplayName("주문 정보 서버 오류에서 액세스 토큰과 금융정보를 숨긴다")
	void 주문_정보_서버_오류에서_액세스_토큰과_금융정보를_숨긴다() {
		정상_토큰_발급_응답을_준비한다();
		server.expect(requestTo(BASE_URL + "/api/v1/commissions"))
				.andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
						.contentType(MediaType.APPLICATION_JSON)
						.body("{\"privateRate\": \"0.123456789\"}"));

		assertThatThrownBy(() -> commissionsClient.getCommissions(ACCOUNT_SEQ))
				.isInstanceOf(TossOrderInfoException.class)
				.hasMessage("토스증권 매매 수수료 조회에 실패했습니다. HTTP 상태: 500")
				.hasMessageNotContaining("0.123456789")
				.hasMessageNotContaining(ACCESS_TOKEN)
				.hasNoCause();
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
	 * 가짜 주문 정보 서버가 국내와 미국 시장의 일반적인 수수료 목록을 반환하도록 준비합니다.
	 */
	private void 정상_수수료_응답을_준비한다() {
		server.expect(requestTo(BASE_URL + "/api/v1/commissions"))
				.andExpect(method(HttpMethod.GET))
				.andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
				.andExpect(header("X-Tossinvest-Account", Long.toString(ACCOUNT_SEQ)))
				.andRespond(withSuccess("""
						{
						  "result": [
						    {
						      "marketCountry": "KR",
						      "commissionRate": "0.00015",
						      "startDate": "2026-01-01",
						      "endDate": "2026-12-31"
						    },
						    {
						      "marketCountry": "US",
						      "commissionRate": "0.001",
						      "startDate": null,
						      "endDate": null
						    }
						  ]
						}
						""", MediaType.APPLICATION_JSON));
	}

	/**
	 * 가짜 주문 정보 서버가 지정된 값으로 한 시장의 수수료 항목을 반환하도록 준비합니다.
	 *
	 * @param marketCountry 반환할 시장 국가 코드
	 * @param commissionRate 반환할 문자열 수수료율
	 * @param startDate 반환할 시작일 또는 null
	 * @param endDate 반환할 종료일 또는 null
	 */
	private void 수수료_응답을_준비한다(
			String marketCountry,
			String commissionRate,
			String startDate,
			String endDate) {
		server.expect(requestTo(BASE_URL + "/api/v1/commissions"))
				.andRespond(withSuccess("""
						{
						  "result": [
						    {
						      "marketCountry": "%s",
						      "commissionRate": "%s",
						      "startDate": %s,
						      "endDate": %s
						    }
						  ]
						}
						""".formatted(
							marketCountry,
							commissionRate,
							jsonStringOrNull(startDate),
							jsonStringOrNull(endDate)), MediaType.APPLICATION_JSON));
	}

	/**
	 * 테스트 날짜 문자열을 JSON 문자열로 감싸고 null이면 JSON null을 만듭니다.
	 *
	 * @param value JSON에 넣을 문자열 또는 null
	 * @return JSON 문자열 표현 또는 null 리터럴
	 */
	private String jsonStringOrNull(String value) {
		return value == null ? "null" : "\"" + value + "\"";
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
