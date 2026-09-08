package com.jusika.backend.marketcalendar;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.jusika.backend.toss.marketinfo.TossUsMarketCalendarClient;

/**
 * 모바일 앱과 내부 안전 검증 서비스가 미국 장 운영 일정을 조회할 HTTP 주소를 제공합니다.
 */
@RestController
@RequestMapping("/api/market/us")
public class UsMarketCalendarController {

	private final TossUsMarketCalendarClient marketCalendarClient;

	/**
	 * 실제 미국 장 운영 일정 조회를 담당하는 토스증권 클라이언트를 전달받습니다.
	 *
	 * @param marketCalendarClient 토스증권 미국 장 운영 일정 조회 클라이언트
	 */
	public UsMarketCalendarController(TossUsMarketCalendarClient marketCalendarClient) {
		this.marketCalendarClient = marketCalendarClient;
	}

	/**
	 * 선택한 미국 현지 날짜의 휴장 여부와 세션별 한국 표준시 운영 시간을 조회합니다.
	 *
	 * @param date 선택한 미국 현지 날짜이며 없으면 토스증권의 현재 기준일
	 * @return 기준일과 직전·다음 영업일의 미국 시장 운영 정보
	 */
	@GetMapping("/calendar")
	public UsMarketCalendarResponse getMarketCalendar(
			@RequestParam(required = false)
			@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
		return marketCalendarClient.getMarketCalendar(date);
	}
}
