package com.jusika.backend.toss.asset;

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

import com.jusika.backend.holding.HoldingsResponse;
import com.jusika.backend.toss.TossApiProperties;
import com.jusika.backend.toss.auth.TossAccessTokenProvider;
import com.jusika.backend.toss.auth.TossAuthClient;

/**
 * 실제 토스증권 서버 대신 가짜 HTTP 서버로 보유주식 요청과 숫자 변환을 검사합니다.
 */
class TossHoldingsClientTests {

	private static final String BASE_URL = "https://toss.example";
	private static final String ACCESS_TOKEN = "노출되면-안되는-테스트-토큰";
	private static final long ACCOUNT_SEQ = 1L;

	private MockRestServiceServer server;
	private TossHoldingsClient holdingsClient;

	/**
	 * 각 테스트에서 사용할 가짜 토스증권 서버와 보유주식 클라이언트를 준비합니다.
	 */
	@BeforeEach
	void 각_테스트에_필요한_가짜_보유주식_서버를_준비한다() {
		RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
		server = MockRestServiceServer.bindTo(builder).build();
		RestClient restClient = builder.build();
		TossAuthClient authClient = new TossAuthClient(restClient, 테스트_인증정보를_만든다());
		Clock clock = Clock.fixed(Instant.parse("2026-09-04T00:00:00Z"), ZoneOffset.UTC);
		TossAccessTokenProvider tokenProvider = new TossAccessTokenProvider(authClient, clock);
		holdingsClient = new TossHoldingsClient(restClient, tokenProvider);
	}

	/**
	 * 계좌 식별 헤더로 보유주식을 조회하고 모든 문자열 숫자를 BigDecimal로 바꾸는지 확인합니다.
	 */
	@Test
	@DisplayName("계좌 식별값으로 삼성전자 보유내역과 평가손익을 조회한다")
	void 계좌_식별값으로_삼성전자_보유내역과_평가손익을_조회한다() {
		정상_토큰_발급_응답을_준비한다();
		server.expect(requestTo(BASE_URL + "/api/v1/holdings"))
				.andExpect(method(HttpMethod.GET))
				.andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
				.andExpect(header("X-Tossinvest-Account", Long.toString(ACCOUNT_SEQ)))
				.andRespond(withSuccess(삼성전자_보유주식_응답을_만든다(), MediaType.APPLICATION_JSON));

		HoldingsResponse response = holdingsClient.getHoldings(ACCOUNT_SEQ);

		assertThat(response.accountSeq()).isEqualTo(ACCOUNT_SEQ);
		assertThat(response.totalPurchaseAmount().krw()).isEqualByComparingTo("6500000");
		assertThat(response.totalPurchaseAmount().usd()).isNull();
		assertThat(response.marketValue().amount().krw()).isEqualByComparingTo("7200000");
		assertThat(response.profitLoss().rate()).isEqualByComparingTo("0.1077");
		assertThat(response.dailyProfitLoss().amount().krw()).isEqualByComparingTo("100000");
		assertThat(response.items()).singleElement().satisfies(item -> {
			assertThat(item.symbol()).isEqualTo("005930");
			assertThat(item.name()).isEqualTo("삼성전자");
			assertThat(item.quantity()).isEqualByComparingTo("100");
			assertThat(item.averagePurchasePrice()).isEqualByComparingTo("65000");
			assertThat(item.cost().tax()).isEqualByComparingTo("135600");
		});
		server.verify();
	}

	/**
	 * 보유 종목이 없는 정상 계좌의 0원 요약과 빈 종목 목록을 그대로 처리하는지 확인합니다.
	 */
	@Test
	@DisplayName("보유주식이 없으면 0원 요약과 빈 목록을 반환한다")
	void 보유주식이_없으면_0원_요약과_빈_목록을_반환한다() {
		정상_토큰_발급_응답을_준비한다();
		server.expect(requestTo(BASE_URL + "/api/v1/holdings"))
				.andRespond(withSuccess(빈_보유주식_응답을_만든다(), MediaType.APPLICATION_JSON));

		HoldingsResponse response = holdingsClient.getHoldings(ACCOUNT_SEQ);

		assertThat(response.totalPurchaseAmount().krw()).isEqualByComparingTo("0");
		assertThat(response.marketValue().amount().usd()).isNull();
		assertThat(response.profitLoss().rate()).isEqualByComparingTo("0");
		assertThat(response.items()).isEmpty();
		server.verify();
	}

	/**
	 * 0이나 음수인 계좌 식별값을 외부 서버에 보내기 전에 차단하는지 확인합니다.
	 */
	@Test
	@DisplayName("잘못된 계좌 식별값은 토스증권 호출 전에 차단한다")
	void 잘못된_계좌_식별값은_토스증권_호출_전에_차단한다() {
		assertThatThrownBy(() -> holdingsClient.getHoldings(0))
				.isInstanceOf(TossAssetException.class)
				.hasMessage("계좌 식별값은 1 이상이어야 합니다.");
		server.verify();
	}

	/**
	 * 숫자가 아닌 금융값을 계산에 사용하지 않고 안전한 응답 오류로 처리하는지 확인합니다.
	 */
	@Test
	@DisplayName("숫자가 아닌 보유주식 금액은 안전하게 거절한다")
	void 숫자가_아닌_보유주식_금액은_안전하게_거절한다() {
		정상_토큰_발급_응답을_준비한다();
		server.expect(requestTo(BASE_URL + "/api/v1/holdings"))
				.andRespond(withSuccess(
						삼성전자_보유주식_응답을_만든다().replace("\"quantity\": \"100\"", "\"quantity\": \"백주\""),
						MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> holdingsClient.getHoldings(ACCOUNT_SEQ))
				.isInstanceOf(TossAssetException.class)
				.hasMessage("토스증권 보유주식 응답의 숫자 형식이 올바르지 않습니다.")
				.hasMessageNotContaining("백주");
		server.verify();
	}

	/**
	 * 토스증권 오류가 액세스 토큰과 금융정보를 포함하지 않는 안전한 예외로 바뀌는지 확인합니다.
	 */
	@Test
	@DisplayName("보유자산 서버 오류에서 액세스 토큰과 금융정보를 숨긴다")
	void 보유자산_서버_오류에서_액세스_토큰과_금융정보를_숨긴다() {
		정상_토큰_발급_응답을_준비한다();
		server.expect(requestTo(BASE_URL + "/api/v1/holdings"))
				.andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
						.contentType(MediaType.APPLICATION_JSON)
						.body("{\"privateAmount\": \"987654321\"}"));

		assertThatThrownBy(() -> holdingsClient.getHoldings(ACCOUNT_SEQ))
				.isInstanceOf(TossAssetException.class)
				.hasMessage("토스증권 보유주식 조회에 실패했습니다. HTTP 상태: 500")
				.hasMessageNotContaining("987654321")
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
	 * 국내 주식 한 종목이 포함된 토스증권 보유주식 성공 응답을 만듭니다.
	 *
	 * @return 문자열 숫자로 구성된 가짜 성공 응답 JSON
	 */
	private String 삼성전자_보유주식_응답을_만든다() {
		return """
				{
				  "result": {
				    "totalPurchaseAmount": {"krw": "6500000", "usd": null},
				    "marketValue": {
				      "amount": {"krw": "7200000", "usd": null},
				      "amountAfterCost": {"krw": "7050000", "usd": null}
				    },
				    "profitLoss": {
				      "amount": {"krw": "700000", "usd": null},
				      "amountAfterCost": {"krw": "550000", "usd": null},
				      "rate": "0.1077",
				      "rateAfterCost": "0.0846"
				    },
				    "dailyProfitLoss": {
				      "amount": {"krw": "100000", "usd": null},
				      "rate": "0.0141"
				    },
				    "items": [
				      {
				        "symbol": "005930",
				        "name": "삼성전자",
				        "marketCountry": "KR",
				        "currency": "KRW",
				        "quantity": "100",
				        "lastPrice": "72000",
				        "averagePurchasePrice": "65000",
				        "marketValue": {
				          "purchaseAmount": "6500000",
				          "amount": "7200000",
				          "amountAfterCost": "7050000"
				        },
				        "profitLoss": {
				          "amount": "700000",
				          "amountAfterCost": "550000",
				          "rate": "0.1077",
				          "rateAfterCost": "0.0846"
				        },
				        "dailyProfitLoss": {"amount": "100000", "rate": "0.0141"},
				        "cost": {"commission": "14400", "tax": "135600"}
				      }
				    ]
				  }
				}
				""";
	}

	/**
	 * 보유 종목 없이 모든 원화 요약값이 0인 정상 응답을 만듭니다.
	 *
	 * @return 빈 보유주식 목록이 포함된 가짜 성공 응답 JSON
	 */
	private String 빈_보유주식_응답을_만든다() {
		return """
				{
				  "result": {
				    "totalPurchaseAmount": {"krw": "0", "usd": null},
				    "marketValue": {
				      "amount": {"krw": "0", "usd": null},
				      "amountAfterCost": {"krw": "0", "usd": null}
				    },
				    "profitLoss": {
				      "amount": {"krw": "0", "usd": null},
				      "amountAfterCost": {"krw": "0", "usd": null},
				      "rate": "0",
				      "rateAfterCost": "0"
				    },
				    "dailyProfitLoss": {
				      "amount": {"krw": "0", "usd": null},
				      "rate": "0"
				    },
				    "items": []
				  }
				}
				""";
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
