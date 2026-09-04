package com.jusika.backend.toss.auth;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.jusika.backend.toss.TossApiProperties;

/**
 * 토스증권 OAuth 서버와 통신해 액세스 토큰을 발급받습니다.
 */
@Component
public class TossAuthClient {

	private static final String CLIENT_CREDENTIALS = "client_credentials";

	private final RestClient restClient;
	private final TossApiProperties properties;

	/**
	 * 인증 요청에 사용할 REST 클라이언트와 비밀 설정을 전달받습니다.
	 *
	 * @param tossRestClient 토스증권 API 전용 REST 클라이언트
	 * @param properties 토스증권 API 연결 및 인증 설정
	 */
	public TossAuthClient(RestClient tossRestClient, TossApiProperties properties) {
		this.restClient = tossRestClient;
		this.properties = properties;
	}

	/**
	 * 클라이언트 아이디와 비밀키를 토스증권에 전송해 액세스 토큰을 발급받습니다.
	 *
	 * @return 토스증권 API 호출에 필요한 토큰 정보
	 * @throws TossAuthenticationException 설정이 없거나 인증 서버가 요청을 거절한 경우
	 */
	public TossTokenResponse issueAccessToken() {
		validateCredentials();

		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("grant_type", CLIENT_CREDENTIALS);
		form.add("client_id", properties.clientId());
		form.add("client_secret", properties.clientSecret());

		try {
			TossTokenResponse response = restClient.post()
					.uri("/oauth2/token")
					.contentType(MediaType.APPLICATION_FORM_URLENCODED)
					.body(form)
					.retrieve()
					.body(TossTokenResponse.class);

			return validateResponse(response);
		} catch (RestClientResponseException exception) {
			throw new TossAuthenticationException(
					"토스증권 인증에 실패했습니다. HTTP 상태: " + exception.getStatusCode().value(),
					exception);
		} catch (TossAuthenticationException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			throw new TossAuthenticationException("토스증권 인증 서버와 통신하지 못했습니다.", exception);
		}
	}

	/**
	 * 인증 요청 전에 필수 환경변수가 빠지지 않았는지 확인합니다.
	 */
	private void validateCredentials() {
		if (isBlank(properties.clientId()) || isBlank(properties.clientSecret())) {
			throw new TossAuthenticationException(
					"토스증권 인증정보가 없습니다. TOSSINVEST_CLIENT_ID와 TOSSINVEST_CLIENT_SECRET을 확인하세요.");
		}
	}

	/**
	 * 인증 서버 응답에 실제로 사용할 수 있는 토큰 정보가 들어 있는지 확인합니다.
	 *
	 * @param response 인증 서버가 반환한 응답
	 * @return 검증을 통과한 토큰 응답
	 */
	private TossTokenResponse validateResponse(TossTokenResponse response) {
		if (response == null
				|| isBlank(response.accessToken())
				|| !"Bearer".equalsIgnoreCase(response.tokenType())
				|| response.expiresIn() <= 0) {
			throw new TossAuthenticationException("토스증권 인증 응답 형식이 올바르지 않습니다.");
		}
		return response;
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
