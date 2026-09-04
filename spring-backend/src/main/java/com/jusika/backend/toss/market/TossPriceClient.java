package com.jusika.backend.toss.market;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.jusika.backend.stock.StockPriceResponse;
import com.jusika.backend.toss.auth.TossAccessTokenProvider;

/**
 * 토스증권 현재가 API를 호출하고 응답을 우리 서버의 현재가 형식으로 변환합니다.
 */
@Component
public class TossPriceClient {

	private final RestClient restClient;
	private final TossAccessTokenProvider tokenProvider;

	/**
	 * 토스증권 전용 REST 클라이언트와 토큰 공급자를 전달받습니다.
	 *
	 * @param tossRestClient 토스증권 API 전용 REST 클라이언트
	 * @param tokenProvider 유효한 액세스 토큰을 제공하는 객체
	 */
	public TossPriceClient(RestClient tossRestClient, TossAccessTokenProvider tokenProvider) {
		this.restClient = tossRestClient;
		this.tokenProvider = tokenProvider;
	}

	/**
	 * 하나의 종목 코드를 토스증권에 전달해 현재가를 조회합니다.
	 *
	 * @param symbol 조회할 국내 또는 해외 주식 종목 코드
	 * @return 숫자 가격과 통화, 시세 시각이 포함된 현재가
	 * @throws TossMarketDataException 서버 호출에 실패하거나 응답 형식이 올바르지 않은 경우
	 */
	public StockPriceResponse getCurrentPrice(String symbol) {
		validateSymbol(symbol);

		try {
			TossPriceApiResponse response = restClient.get()
					.uri(uriBuilder -> uriBuilder
							.path("/api/v1/prices")
							.queryParam("symbols", symbol)
							.build())
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenProvider.getAccessToken())
					.retrieve()
					.body(TossPriceApiResponse.class);

			return convertResponse(response, symbol);
		} catch (RestClientResponseException exception) {
			throw new TossMarketDataException(
					"토스증권 현재가 조회에 실패했습니다. HTTP 상태: " + exception.getStatusCode().value(),
					exception);
		} catch (TossMarketDataException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			throw new TossMarketDataException("토스증권 시세 서버와 통신하지 못했습니다.", exception);
		}
	}

	/**
	 * 외부 API에 전달하기 전에 종목 코드가 토스증권의 허용 형식인지 확인합니다.
	 *
	 * @param symbol 검사할 종목 코드
	 */
	private void validateSymbol(String symbol) {
		if (symbol == null || !symbol.matches("^[A-Za-z0-9.\\-]+$")) {
			throw new TossMarketDataException("종목 코드 형식이 올바르지 않습니다.");
		}
	}

	/**
	 * 토스증권의 목록 응답에서 요청 종목을 찾아 우리 서버의 숫자 가격 형식으로 바꿉니다.
	 *
	 * @param response 토스증권이 반환한 현재가 목록
	 * @param requestedSymbol 사용자가 요청한 종목 코드
	 * @return 검증과 변환을 마친 현재가
	 */
	private StockPriceResponse convertResponse(TossPriceApiResponse response, String requestedSymbol) {
		List<TossPriceItem> prices = response == null ? null : response.result();
		if (prices == null) {
			throw new TossMarketDataException("토스증권 현재가 응답 형식이 올바르지 않습니다.");
		}

		TossPriceItem item = prices.stream()
				.filter(price -> requestedSymbol.equalsIgnoreCase(price.symbol()))
				.findFirst()
				.orElseThrow(() -> new TossMarketDataException("요청한 종목의 현재가를 찾지 못했습니다."));

		try {
			if (item.lastPrice() == null || item.currency() == null || item.timestamp() == null) {
				throw new TossMarketDataException("토스증권 현재가 응답에 필수 값이 없습니다.");
			}
			return new StockPriceResponse(
					item.symbol(),
					new BigDecimal(item.lastPrice()),
					item.currency(),
					OffsetDateTime.parse(item.timestamp()));
		} catch (NumberFormatException | DateTimeParseException exception) {
			throw new TossMarketDataException("토스증권 현재가의 숫자 또는 시각 형식이 올바르지 않습니다.", exception);
		}
	}
}
