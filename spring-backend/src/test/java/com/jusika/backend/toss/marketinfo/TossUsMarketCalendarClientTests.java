package com.jusika.backend.toss.marketinfo;

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
import java.time.OffsetDateTime;
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

import com.jusika.backend.marketcalendar.UsMarketCalendarResponse;
import com.jusika.backend.toss.TossApiProperties;
import com.jusika.backend.toss.auth.TossAccessTokenProvider;
import com.jusika.backend.toss.auth.TossAuthClient;

/**
 * 실제 토스증권 서버 대신 가짜 HTTP 서버로 미국 장 운영 일정 요청과 응답 검증을 검사합니다.
 */
class TossUsMarketCalendarClientTests {

	private static final String BASE_URL = "https://toss.example";
	private static final String ACCESS_TOKEN = "노출되면-안되는-테스트-토큰";
	private static final LocalDate REQUESTED_DATE = LocalDate.parse("2026-03-25");

	private MockRestServiceServer server;
	private TossUsMarketCalendarClient marketCalendarClient;

	/**
	 * 각 테스트에서 사용할 가짜 토스증권 서버와 미국 장 운영 일정 클라이언트를 준비합니다.
	 */
	@BeforeEach
	void 각_테스트에_필요한_가짜_미국_장_운영_서버를_준비한다() {
		RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
		server = MockRestServiceServer.bindTo(builder).build();
		RestClient restClient = builder.build();
		TossAuthClient authClient = new TossAuthClient(restClient, 테스트_인증정보를_만든다());
		Clock clock = Clock.fixed(Instant.parse("2026-03-25T00:00:00Z"), ZoneOffset.UTC);
		TossAccessTokenProvider tokenProvider = new TossAccessTokenProvider(authClient, clock);
		marketCalendarClient = new TossUsMarketCalendarClient(restClient, tokenProvider);
	}

	/**
	 * 미국 현지 날짜와 인증 헤더로 네 거래 세션을 조회하고 한국 표준시로 변환하는지 검사합니다.
	 */
	@Test
	@DisplayName("지정한 날짜의 미국 장 운영 시간을 인증 헤더와 함께 조회한다")
	void 지정한_날짜의_미국_장_운영_시간을_인증_헤더와_함께_조회한다() {
		정상_토큰_발급_응답을_준비한다();
		server.expect(requestTo(BASE_URL + "/api/v1/market-calendar/US?date=2026-03-25"))
				.andExpect(method(HttpMethod.GET))
				.andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
				.andRespond(withSuccess(정상_미국_장_운영_응답(), MediaType.APPLICATION_JSON));

		UsMarketCalendarResponse response = marketCalendarClient.getMarketCalendar(REQUESTED_DATE);

		assertThat(response.today().date()).isEqualTo(REQUESTED_DATE);
		assertThat(response.today().businessDay()).isTrue();
		assertThat(response.today().dayMarket().startTime())
				.isEqualTo(OffsetDateTime.parse("2026-03-25T09:00:00+09:00"));
		assertThat(response.today().regularMarket().endTime())
				.isEqualTo(OffsetDateTime.parse("2026-03-26T05:00:00+09:00"));
		assertThat(response.previousBusinessDay().date())
				.isEqualTo(LocalDate.parse("2026-03-24"));
		assertThat(response.nextBusinessDay().date())
				.isEqualTo(LocalDate.parse("2026-03-26"));
		server.verify();
	}

	/**
	 * 선택 날짜가 없으면 토스증권의 현재 기준일을 사용하도록 날짜 쿼리를 생략하는지 검사합니다.
	 */
	@Test
	@DisplayName("현재 기준 미국 장 운영 일정은 날짜 쿼리 없이 조회한다")
	void 현재_기준_미국_장_운영_일정은_날짜_쿼리_없이_조회한다() {
		정상_토큰_발급_응답을_준비한다();
		server.expect(requestTo(BASE_URL + "/api/v1/market-calendar/US"))
				.andRespond(withSuccess(정상_미국_장_운영_응답(), MediaType.APPLICATION_JSON));

		UsMarketCalendarResponse response = marketCalendarClient.getMarketCalendar(null);

		assertThat(response.today().date()).isEqualTo(REQUESTED_DATE);
		server.verify();
	}

	/**
	 * 당일 네 세션이 모두 없으면 정상 응답을 휴장일로 표시하는지 검사합니다.
	 */
	@Test
	@DisplayName("세션이 없는 미국 시장 날짜를 휴장일로 표시한다")
	void 세션이_없는_미국_시장_날짜를_휴장일로_표시한다() {
		정상_토큰_발급_응답을_준비한다();
		server.expect(requestTo(BASE_URL + "/api/v1/market-calendar/US?date=2026-03-25"))
				.andRespond(withSuccess(미국_시장_휴장일_응답(), MediaType.APPLICATION_JSON));

		UsMarketCalendarResponse response = marketCalendarClient.getMarketCalendar(REQUESTED_DATE);

		assertThat(response.today().businessDay()).isFalse();
		assertThat(response.today().dayMarket()).isNull();
		assertThat(response.today().preMarket()).isNull();
		assertThat(response.today().regularMarket()).isNull();
		assertThat(response.today().afterMarket()).isNull();
		server.verify();
	}

	/**
	 * 기준일이나 직전·다음 영업일이 빠진 응답은 안전하게 거절하는지 검사합니다.
	 */
	@Test
	@DisplayName("필수 날짜가 없는 미국 장 운영 응답을 거절한다")
	void 필수_날짜가_없는_미국_장_운영_응답을_거절한다() {
		정상_토큰_발급_응답을_준비한다();
		server.expect(requestTo(BASE_URL + "/api/v1/market-calendar/US?date=2026-03-25"))
				.andRespond(withSuccess("{\"result\":{\"today\":null}}", MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> marketCalendarClient.getMarketCalendar(REQUESTED_DATE))
				.isInstanceOf(TossMarketInfoException.class)
				.hasMessage("토스증권 미국 장 운영 응답에 필수 값이 없습니다.");
		server.verify();
	}

	/**
	 * 토스증권이 요청과 다른 기준일이나 뒤집힌 전후 날짜를 반환하면 사용하지 않는지 검사합니다.
	 */
	@Test
	@DisplayName("날짜 관계가 잘못된 미국 장 운영 응답을 거절한다")
	void 날짜_관계가_잘못된_미국_장_운영_응답을_거절한다() {
		정상_토큰_발급_응답을_준비한다();
		server.expect(requestTo(BASE_URL + "/api/v1/market-calendar/US?date=2026-03-25"))
				.andRespond(withSuccess(
						정상_미국_장_운영_응답().replace(
								"\"date\": \"2026-03-25\"",
								"\"date\": \"2026-03-27\""),
						MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> marketCalendarClient.getMarketCalendar(REQUESTED_DATE))
				.isInstanceOf(TossMarketInfoException.class)
				.hasMessage("토스증권 미국 장 운영 응답의 날짜 관계가 올바르지 않습니다.");
		server.verify();
	}

	/**
	 * 한국 표준시가 아니거나 종료보다 늦은 시작 시각을 올바르지 않은 세션으로 거절하는지 검사합니다.
	 */
	@Test
	@DisplayName("범위가 잘못된 미국 장 운영 세션을 거절한다")
	void 범위가_잘못된_미국_장_운영_세션을_거절한다() {
		정상_토큰_발급_응답을_준비한다();
		server.expect(requestTo(BASE_URL + "/api/v1/market-calendar/US?date=2026-03-25"))
				.andRespond(withSuccess(
						정상_미국_장_운영_응답().replace(
								"2026-03-25T09:00:00+09:00",
								"2026-03-25T18:00:00+09:00"),
						MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> marketCalendarClient.getMarketCalendar(REQUESTED_DATE))
				.isInstanceOf(TossMarketInfoException.class)
				.hasMessage("토스증권 미국 장 운영 세션의 시각 범위가 올바르지 않습니다.");
		server.verify();
	}

	/**
	 * 토스증권 오류를 액세스 토큰이 포함되지 않은 안전한 내부 예외로 바꾸는지 검사합니다.
	 */
	@Test
	@DisplayName("미국 장 운영 서버 오류에서 액세스 토큰을 숨긴다")
	void 미국_장_운영_서버_오류에서_액세스_토큰을_숨긴다() {
		정상_토큰_발급_응답을_준비한다();
		server.expect(requestTo(BASE_URL + "/api/v1/market-calendar/US?date=2026-03-25"))
				.andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

		assertThatThrownBy(() -> marketCalendarClient.getMarketCalendar(REQUESTED_DATE))
				.isInstanceOf(TossMarketInfoException.class)
				.hasMessage("토스증권 미국 장 운영 정보 조회에 실패했습니다. HTTP 상태: 500")
				.hasMessageNotContaining(ACCESS_TOKEN);
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
	 * 가짜 토스증권 서버가 반환할 세 영업일의 정상 미국 장 운영 JSON을 만듭니다.
	 *
	 * @return 네 세션과 전후 영업일이 포함된 응답 본문
	 */
	private String 정상_미국_장_운영_응답() {
		return """
				{
				  "result": {
				    "today": {
				      "date": "2026-03-25",
				      "dayMarket": {
				        "startTime": "2026-03-25T09:00:00+09:00",
				        "endTime": "2026-03-25T16:50:00+09:00"
				      },
				      "preMarket": {
				        "startTime": "2026-03-25T17:00:00+09:00",
				        "endTime": "2026-03-25T22:30:00+09:00"
				      },
				      "regularMarket": {
				        "startTime": "2026-03-25T22:30:00+09:00",
				        "endTime": "2026-03-26T05:00:00+09:00"
				      },
				      "afterMarket": {
				        "startTime": "2026-03-26T05:00:00+09:00",
				        "endTime": "2026-03-26T07:00:00+09:00"
				      }
				    },
				    "previousBusinessDay": {
				      "date": "2026-03-24",
				      "dayMarket": null,
				      "preMarket": null,
				      "regularMarket": {
				        "startTime": "2026-03-24T22:30:00+09:00",
				        "endTime": "2026-03-25T05:00:00+09:00"
				      },
				      "afterMarket": null
				    },
				    "nextBusinessDay": {
				      "date": "2026-03-26",
				      "dayMarket": null,
				      "preMarket": null,
				      "regularMarket": {
				        "startTime": "2026-03-26T22:30:00+09:00",
				        "endTime": "2026-03-27T05:00:00+09:00"
				      },
				      "afterMarket": null
				    }
				  }
				}
				""";
	}

	/**
	 * 가짜 토스증권 서버가 기준일만 휴장으로 반환할 JSON을 만듭니다.
	 *
	 * @return 당일 네 세션이 모두 null인 응답 본문
	 */
	private String 미국_시장_휴장일_응답() {
		return 정상_미국_장_운영_응답().replace("""
				      "dayMarket": {
				        "startTime": "2026-03-25T09:00:00+09:00",
				        "endTime": "2026-03-25T16:50:00+09:00"
				      },
				      "preMarket": {
				        "startTime": "2026-03-25T17:00:00+09:00",
				        "endTime": "2026-03-25T22:30:00+09:00"
				      },
				      "regularMarket": {
				        "startTime": "2026-03-25T22:30:00+09:00",
				        "endTime": "2026-03-26T05:00:00+09:00"
				      },
				      "afterMarket": {
				        "startTime": "2026-03-26T05:00:00+09:00",
				        "endTime": "2026-03-26T07:00:00+09:00"
				      }
				""", """
				      "dayMarket": null,
				      "preMarket": null,
				      "regularMarket": null,
				      "afterMarket": null
				""");
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
