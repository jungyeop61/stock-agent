package com.jusika.backend.toss.marketinfo;

import java.net.URI;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriBuilder;

import com.jusika.backend.marketcalendar.UsMarketCalendarResponse;
import com.jusika.backend.marketcalendar.UsMarketDayResponse;
import com.jusika.backend.marketcalendar.UsMarketSessionResponse;
import com.jusika.backend.toss.auth.TossAccessTokenProvider;
import com.jusika.backend.toss.marketinfo.TossUsMarketCalendarApiResponse.TossUsMarketCalendarResult;
import com.jusika.backend.toss.marketinfo.TossUsMarketCalendarApiResponse.TossUsMarketDay;
import com.jusika.backend.toss.marketinfo.TossUsMarketCalendarApiResponse.TossUsMarketSession;

/**
 * 토스증권 미국 장 운영 일정 API를 읽기 전용으로 호출하고 날짜와 시각을 검증합니다.
 */
@Component
public class TossUsMarketCalendarClient {

	private static final ZoneOffset KOREA_OFFSET = ZoneOffset.ofHours(9);

	private final RestClient restClient;
	private final TossAccessTokenProvider tokenProvider;

	/**
	 * 토스증권 전용 REST 클라이언트와 토큰 공급자를 전달받습니다.
	 *
	 * @param tossRestClient 토스증권 API 전용 REST 클라이언트
	 * @param tokenProvider 유효한 액세스 토큰을 제공하는 객체
	 */
	public TossUsMarketCalendarClient(
			RestClient tossRestClient,
			TossAccessTokenProvider tokenProvider) {
		this.restClient = tossRestClient;
		this.tokenProvider = tokenProvider;
	}

	/**
	 * 선택한 미국 현지 날짜의 시장 운영 일정과 직전·다음 영업일을 조회합니다.
	 * 이 함수는 계좌나 주문을 변경하지 않습니다.
	 *
	 * @param date 선택한 미국 현지 날짜이며 없으면 토스증권의 현재 기준일
	 * @return 검증을 마친 미국 시장 운영 일정
	 */
	public UsMarketCalendarResponse getMarketCalendar(LocalDate date) {
		try {
			TossUsMarketCalendarApiResponse response = restClient.get()
					.uri(uriBuilder -> buildUri(uriBuilder, date))
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenProvider.getAccessToken())
					.retrieve()
					.body(TossUsMarketCalendarApiResponse.class);
			return convertResponse(response, date);
		} catch (RestClientResponseException exception) {
			throw new TossMarketInfoException(
					"토스증권 미국 장 운영 정보 조회에 실패했습니다. HTTP 상태: "
							+ exception.getStatusCode().value());
		} catch (TossMarketInfoException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			throw new TossMarketInfoException("토스증권 시장 정보 서버와 통신하지 못했습니다.");
		}
	}

	/**
	 * 선택 날짜가 있을 때만 미국 현지 날짜 쿼리를 포함한 조회 주소를 만듭니다.
	 *
	 * @param uriBuilder 토스증권 기본 주소가 설정된 주소 생성기
	 * @param date 선택한 미국 현지 날짜
	 * @return 미국 장 운영 일정 조회 주소
	 */
	private URI buildUri(UriBuilder uriBuilder, LocalDate date) {
		UriBuilder calendarUri = uriBuilder.path("/api/v1/market-calendar/US");
		if (date != null) {
			calendarUri.queryParam("date", date);
		}
		return calendarUri.build();
	}

	/**
	 * 토스증권 응답의 필수 날짜 관계와 세션 시각을 검사해 내부 응답으로 변환합니다.
	 *
	 * @param response 토스증권이 반환한 원본 미국 장 운영 일정
	 * @param requestedDate 요청에 사용한 선택 날짜
	 * @return 검증을 마친 미국 시장 운영 일정
	 */
	private UsMarketCalendarResponse convertResponse(
			TossUsMarketCalendarApiResponse response,
			LocalDate requestedDate) {
		TossUsMarketCalendarResult result = response == null ? null : response.result();
		if (result == null
				|| result.today() == null
				|| result.previousBusinessDay() == null
				|| result.nextBusinessDay() == null) {
			throw new TossMarketInfoException("토스증권 미국 장 운영 응답에 필수 값이 없습니다.");
		}

		try {
			UsMarketDayResponse today = convertDay(result.today());
			UsMarketDayResponse previousBusinessDay = convertDay(result.previousBusinessDay());
			UsMarketDayResponse nextBusinessDay = convertDay(result.nextBusinessDay());
			validateDateOrder(today, previousBusinessDay, nextBusinessDay, requestedDate);
			return new UsMarketCalendarResponse(today, previousBusinessDay, nextBusinessDay);
		} catch (TossMarketInfoException exception) {
			throw exception;
		} catch (DateTimeException exception) {
			throw new TossMarketInfoException("토스증권 미국 장 운영 응답의 날짜 또는 시각 형식이 올바르지 않습니다.");
		}
	}

	/**
	 * 한 날짜의 네 세션을 검증하고 하나라도 운영되면 영업일로 표시합니다.
	 *
	 * @param day 토스증권이 반환한 하루 운영 정보
	 * @return 검증을 마친 하루 운영 정보
	 */
	private UsMarketDayResponse convertDay(TossUsMarketDay day) {
		if (day.date() == null) {
			throw new TossMarketInfoException("토스증권 미국 장 운영 응답에 날짜가 없습니다.");
		}
		UsMarketSessionResponse dayMarket = convertSession(day.dayMarket());
		UsMarketSessionResponse preMarket = convertSession(day.preMarket());
		UsMarketSessionResponse regularMarket = convertSession(day.regularMarket());
		UsMarketSessionResponse afterMarket = convertSession(day.afterMarket());
		boolean businessDay = dayMarket != null
				|| preMarket != null
				|| regularMarket != null
				|| afterMarket != null;
		return new UsMarketDayResponse(
				LocalDate.parse(day.date()),
				businessDay,
				dayMarket,
				preMarket,
				regularMarket,
				afterMarket);
	}

	/**
	 * 선택 세션의 시작과 종료가 한국 표준시이며 순서가 올바른지 검사합니다.
	 *
	 * @param session 토스증권이 반환한 선택 세션이며 운영하지 않으면 null
	 * @return 검증한 세션이며 운영하지 않으면 null
	 */
	private UsMarketSessionResponse convertSession(TossUsMarketSession session) {
		if (session == null) {
			return null;
		}
		if (session.startTime() == null || session.endTime() == null) {
			throw new TossMarketInfoException("토스증권 미국 장 운영 세션에 필수 시각이 없습니다.");
		}
		OffsetDateTime startTime = OffsetDateTime.parse(session.startTime());
		OffsetDateTime endTime = OffsetDateTime.parse(session.endTime());
		if (!KOREA_OFFSET.equals(startTime.getOffset())
				|| !KOREA_OFFSET.equals(endTime.getOffset())
				|| !startTime.isBefore(endTime)) {
			throw new TossMarketInfoException("토스증권 미국 장 운영 세션의 시각 범위가 올바르지 않습니다.");
		}
		return new UsMarketSessionResponse(startTime, endTime);
	}

	/**
	 * 기준일이 요청 날짜와 같고 직전·다음 영업일이 올바른 순서인지 검사합니다.
	 *
	 * @param today 조회 기준일 운영 정보
	 * @param previousBusinessDay 직전 영업일 운영 정보
	 * @param nextBusinessDay 다음 영업일 운영 정보
	 * @param requestedDate 요청에 사용한 선택 날짜
	 */
	private void validateDateOrder(
			UsMarketDayResponse today,
			UsMarketDayResponse previousBusinessDay,
			UsMarketDayResponse nextBusinessDay,
			LocalDate requestedDate) {
		if ((requestedDate != null && !requestedDate.equals(today.date()))
				|| !previousBusinessDay.date().isBefore(today.date())
				|| !nextBusinessDay.date().isAfter(today.date())
				|| !previousBusinessDay.businessDay()
				|| !nextBusinessDay.businessDay()) {
			throw new TossMarketInfoException("토스증권 미국 장 운영 응답의 날짜 관계가 올바르지 않습니다.");
		}
	}
}
