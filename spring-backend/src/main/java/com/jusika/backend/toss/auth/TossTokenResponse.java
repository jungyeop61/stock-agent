package com.jusika.backend.toss.auth;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 토스증권 OAuth 서버가 반환한 액세스 토큰 정보를 표현합니다.
 *
 * @param accessToken 이후 API 호출에 사용할 액세스 토큰
 * @param tokenType 토큰 전달 방식이며 현재는 Bearer
 * @param expiresIn 토큰 만료까지 남은 시간(초)
 */
public record TossTokenResponse(
		@JsonProperty("access_token") String accessToken,
		@JsonProperty("token_type") String tokenType,
		@JsonProperty("expires_in") long expiresIn) {

	/**
	 * 토큰 응답이 로그에 기록되더라도 액세스 토큰이 노출되지 않도록 가린 문자열을 만듭니다.
	 *
	 * @return 액세스 토큰이 마스킹된 응답 설명
	 */
	@Override
	public String toString() {
		return "TossTokenResponse[accessToken=***, tokenType=" + tokenType
				+ ", expiresIn=" + expiresIn + "]";
	}
}
