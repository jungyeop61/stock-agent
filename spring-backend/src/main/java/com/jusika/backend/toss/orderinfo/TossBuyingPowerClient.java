package com.jusika.backend.toss.orderinfo;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.jusika.backend.buyingpower.BuyingPowerResponse;
import com.jusika.backend.toss.auth.TossAccessTokenProvider;
import com.jusika.backend.toss.orderinfo.TossBuyingPowerApiResponse.TossBuyingPowerResult;

/**
 * 토스증권 매수 가능 금액 API를 호출하고 문자열 금액을 정확한 숫자로 변환합니다.
 */
@Component
public class TossBuyingPowerClient {

	private static final String ACCOUNT_HEADER = "X-Tossinvest-Account";
	private static final Set<String> SUPPORTED_CURRENCIES = Set.of("KRW", "USD");

	private final RestClient restClient;
	private final TossAccessTokenProvider tokenProvider;

	/**
	 * 토스증권 전용 REST 클라이언트와 토큰 공급자를 전달받습니다.
	 *
	 * @param tossRestClient 토스증권 API 전용 REST 클라이언트
	 * @param tokenProvider 유효한 액세스 토큰을 제공하는 객체
	 */
	public TossBuyingPowerClient(RestClient tossRestClient, TossAccessTokenProvider tokenProvider) {
		this.restClient = tossRestClient;
		this.tokenProvider = tokenProvider;
	}

	/**
	 * 지정된 계좌와 통화의 현금 매수 가능 금액을 조회합니다.
	 *
	 * @param accountSeq 계좌 목록 API에서 받은 계좌 식별값
	 * @param currency 조회할 통화 코드인 KRW 또는 USD
	 * @return 숫자로 변환된 현금 매수 가능 금액
	 * @throws TossOrderInfoException 요청값, 서버 호출 또는 응답 형식이 올바르지 않은 경우
	 */
	public BuyingPowerResponse getBuyingPower(long accountSeq, String currency) {
		validateAccountSeq(accountSeq);
		String normalizedCurrency = normalizeCurrency(currency);

		try {
			TossBuyingPowerApiResponse response = restClient.get()
					.uri(uriBuilder -> uriBuilder
							.path("/api/v1/buying-power")
							.queryParam("currency", normalizedCurrency)
							.build())
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenProvider.getAccessToken())
					.header(ACCOUNT_HEADER, Long.toString(accountSeq))
					.retrieve()
					.body(TossBuyingPowerApiResponse.class);

			return convertResponse(response, accountSeq, normalizedCurrency);
		} catch (RestClientResponseException exception) {
			throw new TossOrderInfoException(
					"토스증권 매수 가능 금액 조회에 실패했습니다. HTTP 상태: " + exception.getStatusCode().value());
		} catch (TossOrderInfoException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			throw new TossOrderInfoException("토스증권 주문 정보 서버와 통신하지 못했습니다.");
		}
	}

	/**
	 * 외부 API를 호출하기 전에 계좌 식별값이 양수인지 확인합니다.
	 *
	 * @param accountSeq 검사할 계좌 식별값
	 */
	private void validateAccountSeq(long accountSeq) {
		if (accountSeq <= 0) {
			throw new TossOrderInfoException("계좌 식별값은 1 이상이어야 합니다.");
		}
	}

	/**
	 * 통화 코드의 대소문자를 정리하고 토스증권이 지원하는 원화와 달러인지 확인합니다.
	 *
	 * @param currency 검사할 통화 코드
	 * @return 대문자로 정규화된 통화 코드
	 */
	private String normalizeCurrency(String currency) {
		if (currency == null || currency.isBlank()) {
			throw new TossOrderInfoException("통화 코드는 KRW 또는 USD여야 합니다.");
		}

		String normalizedCurrency = currency.toUpperCase(Locale.ROOT);
		if (!SUPPORTED_CURRENCIES.contains(normalizedCurrency)) {
			throw new TossOrderInfoException("통화 코드는 KRW 또는 USD여야 합니다.");
		}
		return normalizedCurrency;
	}

	/**
	 * 토스증권 응답의 통화와 금액을 확인하고 우리 서버 응답으로 변환합니다.
	 *
	 * @param response 토스증권이 반환한 원본 응답
	 * @param accountSeq 조회에 사용한 계좌 식별값
	 * @param requestedCurrency 요청에 사용한 정규화된 통화 코드
	 * @return 검증과 숫자 변환을 마친 매수 가능 금액
	 */
	private BuyingPowerResponse convertResponse(
			TossBuyingPowerApiResponse response,
			long accountSeq,
			String requestedCurrency) {
		TossBuyingPowerResult result = response == null ? null : response.result();
		if (result == null || result.currency() == null || result.cashBuyingPower() == null) {
			throw new TossOrderInfoException("토스증권 매수 가능 금액 응답 형식이 올바르지 않습니다.");
		}

		String responseCurrency = normalizeCurrency(result.currency());
		if (!requestedCurrency.equals(responseCurrency)) {
			throw new TossOrderInfoException("토스증권이 요청과 다른 통화의 금액을 반환했습니다.");
		}

		try {
			return new BuyingPowerResponse(
					accountSeq,
					responseCurrency,
					new BigDecimal(result.cashBuyingPower()));
		} catch (NumberFormatException exception) {
			throw new TossOrderInfoException("토스증권 매수 가능 금액의 숫자 형식이 올바르지 않습니다.");
		}
	}
}
