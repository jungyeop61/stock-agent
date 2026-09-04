package com.jusika.backend.toss.orderinfo;

import java.math.BigDecimal;
import java.util.Locale;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.jusika.backend.sellablequantity.SellableQuantityResponse;
import com.jusika.backend.toss.auth.TossAccessTokenProvider;
import com.jusika.backend.toss.orderinfo.TossSellableQuantityApiResponse.TossSellableQuantityResult;

/**
 * 토스증권 매도 가능 수량 API를 호출하고 문자열 수량을 정확한 숫자로 변환합니다.
 */
@Component
public class TossSellableQuantityClient {

	private static final String ACCOUNT_HEADER = "X-Tossinvest-Account";

	private final RestClient restClient;
	private final TossAccessTokenProvider tokenProvider;

	/**
	 * 토스증권 전용 REST 클라이언트와 토큰 공급자를 전달받습니다.
	 *
	 * @param tossRestClient 토스증권 API 전용 REST 클라이언트
	 * @param tokenProvider 유효한 액세스 토큰을 제공하는 객체
	 */
	public TossSellableQuantityClient(RestClient tossRestClient, TossAccessTokenProvider tokenProvider) {
		this.restClient = tossRestClient;
		this.tokenProvider = tokenProvider;
	}

	/**
	 * 지정된 계좌에서 특정 종목의 현재 매도 가능 수량을 조회합니다.
	 *
	 * @param accountSeq 계좌 목록 API에서 받은 계좌 식별값
	 * @param symbol 조회할 국내 또는 해외 주식 종목 코드
	 * @return 숫자로 변환된 종목별 매도 가능 수량
	 * @throws TossOrderInfoException 요청값, 서버 호출 또는 응답 형식이 올바르지 않은 경우
	 */
	public SellableQuantityResponse getSellableQuantity(long accountSeq, String symbol) {
		validateAccountSeq(accountSeq);
		String normalizedSymbol = normalizeSymbol(symbol);

		try {
			TossSellableQuantityApiResponse response = restClient.get()
					.uri(uriBuilder -> uriBuilder
							.path("/api/v1/sellable-quantity")
							.queryParam("symbol", normalizedSymbol)
							.build())
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenProvider.getAccessToken())
					.header(ACCOUNT_HEADER, Long.toString(accountSeq))
					.retrieve()
					.body(TossSellableQuantityApiResponse.class);

			return convertResponse(response, accountSeq, normalizedSymbol);
		} catch (RestClientResponseException exception) {
			throw new TossOrderInfoException(
					"토스증권 매도 가능 수량 조회에 실패했습니다. HTTP 상태: " + exception.getStatusCode().value());
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
	 * 종목 코드의 허용 문자를 검사하고 영문자를 대문자로 정규화합니다.
	 *
	 * @param symbol 검사할 국내 또는 해외 주식 종목 코드
	 * @return 영문자가 대문자로 정규화된 종목 코드
	 */
	private String normalizeSymbol(String symbol) {
		if (symbol == null || !symbol.matches("^[A-Za-z0-9.\\-]+$")) {
			throw new TossOrderInfoException("종목 코드 형식이 올바르지 않습니다.");
		}
		return symbol.toUpperCase(Locale.ROOT);
	}

	/**
	 * 토스증권의 문자열 수량을 우리 서버가 계산할 수 있는 숫자 응답으로 변환합니다.
	 *
	 * @param response 토스증권이 반환한 원본 응답
	 * @param accountSeq 조회에 사용한 계좌 식별값
	 * @param symbol 조회에 사용한 정규화된 종목 코드
	 * @return 숫자 변환을 마친 매도 가능 수량
	 */
	private SellableQuantityResponse convertResponse(
			TossSellableQuantityApiResponse response,
			long accountSeq,
			String symbol) {
		TossSellableQuantityResult result = response == null ? null : response.result();
		if (result == null || result.sellableQuantity() == null) {
			throw new TossOrderInfoException("토스증권 매도 가능 수량 응답 형식이 올바르지 않습니다.");
		}

		try {
			BigDecimal sellableQuantity = new BigDecimal(result.sellableQuantity());
			if (sellableQuantity.signum() < 0) {
				throw new TossOrderInfoException("토스증권 매도 가능 수량은 음수일 수 없습니다.");
			}
			return new SellableQuantityResponse(accountSeq, symbol, sellableQuantity);
		} catch (NumberFormatException exception) {
			throw new TossOrderInfoException("토스증권 매도 가능 수량의 숫자 형식이 올바르지 않습니다.");
		}
	}
}
