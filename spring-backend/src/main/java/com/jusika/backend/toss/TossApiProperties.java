package com.jusika.backend.toss;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 설정 파일과 환경변수에서 읽은 토스증권 API 연결 정보를 한곳에 모읍니다.
 *
 * @param baseUrl 토스증권 Open API 기본 주소
 * @param clientId 토스증권에서 발급받은 클라이언트 아이디
 * @param clientSecret 토스증권에서 발급받은 클라이언트 비밀키
 * @param connectTimeout 서버 연결을 기다리는 최대 시간
 * @param readTimeout 서버 응답을 기다리는 최대 시간
 */
@ConfigurationProperties(prefix = "jusika.toss")
public record TossApiProperties(
		URI baseUrl,
		String clientId,
		String clientSecret,
		Duration connectTimeout,
		Duration readTimeout) {

	/**
	 * 설정 객체가 로그에 기록되더라도 클라이언트 비밀키가 노출되지 않도록 가린 문자열을 만듭니다.
	 *
	 * @return 비밀정보가 마스킹된 설정 설명
	 */
	@Override
	public String toString() {
		return "TossApiProperties[baseUrl=" + baseUrl
				+ ", clientId=***, clientSecret=***, connectTimeout=" + connectTimeout
				+ ", readTimeout=" + readTimeout + "]";
	}
}
