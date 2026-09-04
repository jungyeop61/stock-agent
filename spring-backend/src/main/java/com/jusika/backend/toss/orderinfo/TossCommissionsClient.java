package com.jusika.backend.toss.orderinfo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.jusika.backend.commission.CommissionsResponse;
import com.jusika.backend.commission.CommissionsResponse.CommissionItem;
import com.jusika.backend.toss.auth.TossAccessTokenProvider;
import com.jusika.backend.toss.orderinfo.TossCommissionsApiResponse.TossCommissionItem;

/**
 * 토스증권 매매 수수료 API를 호출하고 문자열 수수료율과 날짜를 정확한 형식으로 변환합니다.
 */
@Component
public class TossCommissionsClient {

	private static final String ACCOUNT_HEADER = "X-Tossinvest-Account";
	private static final Set<String> SUPPORTED_MARKETS = Set.of("KR", "US");

	private final RestClient restClient;
	private final TossAccessTokenProvider tokenProvider;

	/**
	 * 토스증권 전용 REST 클라이언트와 토큰 공급자를 전달받습니다.
	 *
	 * @param tossRestClient 토스증권 API 전용 REST 클라이언트
	 * @param tokenProvider 유효한 액세스 토큰을 제공하는 객체
	 */
	public TossCommissionsClient(RestClient tossRestClient, TossAccessTokenProvider tokenProvider) {
		this.restClient = tossRestClient;
		this.tokenProvider = tokenProvider;
	}

	/**
	 * 지정된 계좌에 적용되는 국내와 미국 시장의 매매 수수료를 조회합니다.
	 *
	 * @param accountSeq 계좌 목록 API에서 받은 계좌 식별값
	 * @return 숫자 수수료율과 날짜로 변환된 시장별 수수료 목록
	 * @throws TossOrderInfoException 요청값, 서버 호출 또는 응답 형식이 올바르지 않은 경우
	 */
	public CommissionsResponse getCommissions(long accountSeq) {
		validateAccountSeq(accountSeq);

		try {
			TossCommissionsApiResponse response = restClient.get()
					.uri("/api/v1/commissions")
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenProvider.getAccessToken())
					.header(ACCOUNT_HEADER, Long.toString(accountSeq))
					.retrieve()
					.body(TossCommissionsApiResponse.class);

			return convertResponse(response, accountSeq);
		} catch (RestClientResponseException exception) {
			throw new TossOrderInfoException(
					"토스증권 매매 수수료 조회에 실패했습니다. HTTP 상태: " + exception.getStatusCode().value());
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
	 * 토스증권의 시장별 목록을 우리 서버의 계좌 수수료 응답으로 변환합니다.
	 *
	 * @param response 토스증권이 반환한 원본 응답
	 * @param accountSeq 조회에 사용한 계좌 식별값
	 * @return 모든 문자열 값을 변환한 시장별 수수료 응답
	 */
	private CommissionsResponse convertResponse(TossCommissionsApiResponse response, long accountSeq) {
		List<TossCommissionItem> result = response == null ? null : response.result();
		if (result == null || result.isEmpty()) {
			throw new TossOrderInfoException("토스증권 매매 수수료 응답 형식이 올바르지 않습니다.");
		}

		List<CommissionItem> commissions = result.stream()
				.map(this::convertItem)
				.toList();
		return new CommissionsResponse(accountSeq, commissions);
	}

	/**
	 * 토스증권의 한 시장 수수료 항목을 숫자 수수료율과 날짜로 변환합니다.
	 *
	 * @param item 토스증권이 반환한 한 시장의 수수료 항목
	 * @return 형식 검증과 변환을 마친 시장 수수료 항목
	 */
	private CommissionItem convertItem(TossCommissionItem item) {
		if (item == null
				|| !SUPPORTED_MARKETS.contains(item.marketCountry())
				|| item.commissionRate() == null) {
			throw new TossOrderInfoException("토스증권 매매 수수료 항목에 필수 값이 없습니다.");
		}

		try {
			BigDecimal commissionRate = new BigDecimal(item.commissionRate());
			if (commissionRate.signum() < 0) {
				throw new TossOrderInfoException("토스증권 매매 수수료율은 음수일 수 없습니다.");
			}

			return new CommissionItem(
					item.marketCountry(),
					commissionRate,
					parseNullableDate(item.startDate()),
					parseNullableDate(item.endDate()));
		} catch (NumberFormatException | DateTimeParseException exception) {
			throw new TossOrderInfoException("토스증권 매매 수수료의 숫자 또는 날짜 형식이 올바르지 않습니다.");
		}
	}

	/**
	 * 값이 있으면 ISO 날짜로 변환하고 토스증권이 null을 반환하면 그대로 유지합니다.
	 *
	 * @param value 변환할 YYYY-MM-DD 문자열 또는 null
	 * @return 변환된 날짜 또는 null
	 */
	private LocalDate parseNullableDate(String value) {
		return value == null ? null : LocalDate.parse(value);
	}
}
