package com.jusika.backend.exchangerate;

import java.time.OffsetDateTime;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.jusika.backend.toss.marketinfo.TossExchangeRateClient;

/**
 * 모바일 앱이나 내부 안전 검증 서비스가 참고용 환율을 조회할 HTTP 주소를 제공합니다.
 */
@RestController
@RequestMapping("/api/market")
public class ExchangeRateController {

	private final TossExchangeRateClient exchangeRateClient;

	/**
	 * 실제 환율 조회를 담당하는 토스증권 클라이언트를 전달받습니다.
	 *
	 * @param exchangeRateClient 토스증권 환율 조회 클라이언트
	 */
	public ExchangeRateController(TossExchangeRateClient exchangeRateClient) {
		this.exchangeRateClient = exchangeRateClient;
	}

	/**
	 * 현재 또는 지정 시점의 원화와 달러 사이 환율을 조회합니다.
	 *
	 * @param baseCurrency 한 단위를 환산할 기준 통화
	 * @param quoteCurrency 환산 결과를 표시할 상대 통화
	 * @param dateTime 조회할 선택 시각이며 없으면 현재 유효 환율
	 * @return 토스증권에서 조회하고 검증한 참고용 환율
	 */
	@GetMapping("/exchange-rate")
	public ExchangeRateResponse getExchangeRate(
			@RequestParam String baseCurrency,
			@RequestParam String quoteCurrency,
			@RequestParam(required = false)
			@DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime dateTime) {
		return exchangeRateClient.getExchangeRate(baseCurrency, quoteCurrency, dateTime);
	}
}
