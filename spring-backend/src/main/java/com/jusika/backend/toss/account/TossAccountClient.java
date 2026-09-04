package com.jusika.backend.toss.account;

import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.jusika.backend.account.AccountResponse;
import com.jusika.backend.toss.auth.TossAccessTokenProvider;

/**
 * 토스증권 계좌 목록 API를 호출하고 실제 계좌번호를 가린 안전한 응답으로 변환합니다.
 */
@Component
public class TossAccountClient {

	private static final int VISIBLE_ACCOUNT_NUMBER_LENGTH = 4;

	private final RestClient restClient;
	private final TossAccessTokenProvider tokenProvider;

	/**
	 * 토스증권 전용 REST 클라이언트와 토큰 공급자를 전달받습니다.
	 *
	 * @param tossRestClient 토스증권 API 전용 REST 클라이언트
	 * @param tokenProvider 유효한 액세스 토큰을 제공하는 객체
	 */
	public TossAccountClient(RestClient tossRestClient, TossAccessTokenProvider tokenProvider) {
		this.restClient = tossRestClient;
		this.tokenProvider = tokenProvider;
	}

	/**
	 * 토스증권에서 사용 가능한 계좌 목록을 조회합니다.
	 *
	 * @return 계좌 식별값과 마스킹된 계좌번호가 포함된 목록
	 * @throws TossAccountException 서버 호출에 실패하거나 응답 형식이 올바르지 않은 경우
	 */
	public List<AccountResponse> getAccounts() {
		try {
			TossAccountApiResponse response = restClient.get()
					.uri("/api/v1/accounts")
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenProvider.getAccessToken())
					.retrieve()
					.body(TossAccountApiResponse.class);

			return convertResponse(response);
		} catch (RestClientResponseException exception) {
			throw new TossAccountException(
					"토스증권 계좌 목록 조회에 실패했습니다. HTTP 상태: " + exception.getStatusCode().value());
		} catch (TossAccountException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			throw new TossAccountException("토스증권 계좌 서버와 통신하지 못했습니다.");
		}
	}

	/**
	 * 토스증권 원본 계좌 목록을 검증하고 계좌번호가 가려진 응답 목록으로 바꿉니다.
	 *
	 * @param response 토스증권이 반환한 계좌 목록
	 * @return 외부에 안전하게 반환할 수 있는 계좌 목록
	 */
	private List<AccountResponse> convertResponse(TossAccountApiResponse response) {
		List<TossAccountItem> accounts = response == null ? null : response.result();
		if (accounts == null) {
			throw new TossAccountException("토스증권 계좌 목록 응답 형식이 올바르지 않습니다.");
		}

		return accounts.stream()
				.map(this::convertAccount)
				.toList();
	}

	/**
	 * 개별 계좌의 필수 값을 확인하고 실제 계좌번호 앞부분을 별표로 가립니다.
	 *
	 * @param account 토스증권이 반환한 개별 계좌
	 * @return 계좌번호가 마스킹된 계좌 응답
	 */
	private AccountResponse convertAccount(TossAccountItem account) {
		if (account == null
				|| account.accountSeq() <= 0
				|| isBlank(account.accountNo())
				|| isBlank(account.accountType())) {
			throw new TossAccountException("토스증권 계좌 정보에 필수 값이 없습니다.");
		}

		return new AccountResponse(
				account.accountSeq(),
				maskAccountNumber(account.accountNo()),
				account.accountType());
	}

	/**
	 * 사용자가 자신의 계좌를 구분할 수 있도록 끝 4자리만 남기고 나머지를 별표로 바꿉니다.
	 *
	 * @param accountNumber 가리지 않은 실제 계좌번호
	 * @return 앞부분이 별표로 가려진 계좌번호
	 */
	private String maskAccountNumber(String accountNumber) {
		if (accountNumber.length() <= VISIBLE_ACCOUNT_NUMBER_LENGTH) {
			return "*".repeat(accountNumber.length());
		}

		int hiddenLength = accountNumber.length() - VISIBLE_ACCOUNT_NUMBER_LENGTH;
		return "*".repeat(hiddenLength) + accountNumber.substring(hiddenLength);
	}

	/**
	 * 문자열이 없거나 공백만 있는지 검사합니다.
	 *
	 * @param value 검사할 문자열
	 * @return 값이 비어 있으면 true
	 */
	private boolean isBlank(String value) {
		return value == null || value.isBlank();
	}
}
