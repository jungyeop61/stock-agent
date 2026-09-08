package com.jusika.backend.toss.marketinfo;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Set;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriBuilder;

import com.jusika.backend.exchangerate.ExchangeRateChangeType;
import com.jusika.backend.exchangerate.ExchangeRateRequestException;
import com.jusika.backend.exchangerate.ExchangeRateResponse;
import com.jusika.backend.toss.auth.TossAccessTokenProvider;
import com.jusika.backend.toss.marketinfo.TossExchangeRateApiResponse.TossExchangeRateResult;

/**
 * 토스증권 환율 API를 호출하고 문자열 숫자와 시각을 안전한 내부 형식으로 변환합니다.
 */
@Component
public class TossExchangeRateClient {

	private static final Set<String> SUPPORTED_CURRENCIES = Set.of("KRW", "USD");

	private final RestClient restClient;
	private final TossAccessTokenProvider tokenProvider;

	/**
	 * 토스증권 전용 REST 클라이언트와 토큰 공급자를 전달받습니다.
	 *
	 * @param tossRestClient 토스증권 API 전용 REST 클라이언트
	 * @param tokenProvider 유효한 액세스 토큰을 제공하는 객체
	 */
	public TossExchangeRateClient(RestClient tossRestClient, TossAccessTokenProvider tokenProvider) {
		this.restClient = tossRestClient;
		this.tokenProvider = tokenProvider;
	}

	/**
	 * 현재 또는 지정 시점의 원화와 달러 사이 환율을 조회합니다.
	 *
	 * @param baseCurrency 한 단위를 환산할 기준 통화
	 * @param quoteCurrency 환산 결과를 표시할 상대 통화
	 * @param dateTime 조회할 선택 시각이며 없으면 현재 유효 환율
	 * @return 숫자와 시각 형식 검증을 마친 참고용 환율
	 * @throws ExchangeRateRequestException 통화 조합이 올바르지 않은 경우
	 * @throws TossMarketInfoException 서버 호출이나 응답 형식이 올바르지 않은 경우
	 */
	public ExchangeRateResponse getExchangeRate(
			String baseCurrency,
			String quoteCurrency,
			OffsetDateTime dateTime) {
		String normalizedBaseCurrency = normalizeRequestedCurrency(baseCurrency);
		String normalizedQuoteCurrency = normalizeRequestedCurrency(quoteCurrency);
		if (normalizedBaseCurrency.equals(normalizedQuoteCurrency)) {
			throw new ExchangeRateRequestException("기준 통화와 상대 통화는 서로 달라야 합니다.");
		}

		try {
			TossExchangeRateApiResponse response = restClient.get()
					.uri(uriBuilder -> buildUri(
							uriBuilder,
							normalizedBaseCurrency,
							normalizedQuoteCurrency,
							dateTime))
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenProvider.getAccessToken())
					.retrieve()
					.body(TossExchangeRateApiResponse.class);
			return convertResponse(response, normalizedBaseCurrency, normalizedQuoteCurrency);
		} catch (RestClientResponseException exception) {
			throw new TossMarketInfoException(
					"토스증권 환율 조회에 실패했습니다. HTTP 상태: "
							+ exception.getStatusCode().value());
		} catch (TossMarketInfoException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			throw new TossMarketInfoException("토스증권 시장 정보 서버와 통신하지 못했습니다.");
		}
	}

	/**
	 * 선택 조회 시각의 유무에 맞춰 토스증권 환율 조회 주소를 만듭니다.
	 *
	 * @param uriBuilder 토스증권 기본 주소가 설정된 주소 생성기
	 * @param baseCurrency 정규화된 기준 통화
	 * @param quoteCurrency 정규화된 상대 통화
	 * @param dateTime 조회할 선택 시각
	 * @return 쿼리 파라미터를 모두 포함한 요청 주소
	 */
	private java.net.URI buildUri(
			UriBuilder uriBuilder,
			String baseCurrency,
			String quoteCurrency,
			OffsetDateTime dateTime) {
		UriBuilder exchangeRateUri = uriBuilder
				.path("/api/v1/exchange-rate")
				.queryParam("baseCurrency", baseCurrency)
				.queryParam("quoteCurrency", quoteCurrency);
		if (dateTime != null) {
			exchangeRateUri.queryParam("dateTime", dateTime.toString());
		}
		return exchangeRateUri.build();
	}

	/**
	 * 요청 통화의 대소문자를 정리하고 원화 또는 달러인지 확인합니다.
	 *
	 * @param currency 검사할 요청 통화 코드
	 * @return 대문자로 정규화된 통화 코드
	 */
	private String normalizeRequestedCurrency(String currency) {
		if (currency == null || currency.isBlank()) {
			throw new ExchangeRateRequestException("통화 코드는 KRW 또는 USD여야 합니다.");
		}
		String normalizedCurrency = currency.toUpperCase(Locale.ROOT);
		if (!SUPPORTED_CURRENCIES.contains(normalizedCurrency)) {
			throw new ExchangeRateRequestException("통화 코드는 KRW 또는 USD여야 합니다.");
		}
		return normalizedCurrency;
	}

	/**
	 * 토스증권 응답의 필수 값과 요청 통화 일치 여부를 확인한 뒤 숫자와 시각을 변환합니다.
	 *
	 * @param response 토스증권이 반환한 원본 환율 응답
	 * @param requestedBaseCurrency 요청에 사용한 기준 통화
	 * @param requestedQuoteCurrency 요청에 사용한 상대 통화
	 * @return 검증과 자료형 변환을 마친 환율
	 */
	private ExchangeRateResponse convertResponse(
			TossExchangeRateApiResponse response,
			String requestedBaseCurrency,
			String requestedQuoteCurrency) {
		TossExchangeRateResult result = response == null ? null : response.result();
		if (result == null
				|| result.baseCurrency() == null
				|| result.quoteCurrency() == null
				|| result.rate() == null
				|| result.midRate() == null
				|| result.basisPoint() == null
				|| result.rateChangeType() == null
				|| result.validFrom() == null
				|| result.validUntil() == null) {
			throw new TossMarketInfoException("토스증권 환율 응답에 필수 값이 없습니다.");
		}

		String responseBaseCurrency = result.baseCurrency().toUpperCase(Locale.ROOT);
		String responseQuoteCurrency = result.quoteCurrency().toUpperCase(Locale.ROOT);
		if (!SUPPORTED_CURRENCIES.contains(responseBaseCurrency)
				|| !SUPPORTED_CURRENCIES.contains(responseQuoteCurrency)
				|| !requestedBaseCurrency.equals(responseBaseCurrency)
				|| !requestedQuoteCurrency.equals(responseQuoteCurrency)) {
			throw new TossMarketInfoException("토스증권이 요청과 다른 통화의 환율을 반환했습니다.");
		}

		try {
			BigDecimal rate = new BigDecimal(result.rate());
			BigDecimal midRate = new BigDecimal(result.midRate());
			BigDecimal basisPoint = new BigDecimal(result.basisPoint());
			ExchangeRateChangeType rateChangeType = ExchangeRateChangeType.valueOf(
					result.rateChangeType());
			OffsetDateTime validFrom = OffsetDateTime.parse(result.validFrom());
			OffsetDateTime validUntil = OffsetDateTime.parse(result.validUntil());
			if (rate.signum() <= 0 || midRate.signum() <= 0 || !validFrom.isBefore(validUntil)) {
				throw new TossMarketInfoException("토스증권 환율 응답의 값 범위가 올바르지 않습니다.");
			}
			return new ExchangeRateResponse(
					responseBaseCurrency,
					responseQuoteCurrency,
					rate,
					midRate,
					basisPoint,
					rateChangeType,
					validFrom,
					validUntil);
		} catch (DateTimeParseException | IllegalArgumentException exception) {
			throw new TossMarketInfoException("토스증권 환율의 숫자, 시각 또는 등락 형식이 올바르지 않습니다.");
		}
	}
}
