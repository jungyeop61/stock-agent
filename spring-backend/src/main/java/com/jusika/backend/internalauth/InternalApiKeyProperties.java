package com.jusika.backend.internalauth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 모바일 앱과 파이썬 에이전트의 내부 API 접근을 확인할 키 설정입니다.
 *
 * @param readKey 계좌와 주문 상태 조회에 사용할 읽기 전용 키
 * @param orderKey 미리보기·승인·실행·복구에 사용할 주문 키
 */
@ConfigurationProperties(prefix = "jusika.internal-api")
public record InternalApiKeyProperties(String readKey, String orderKey) {

	/**
	 * 누락된 키를 빈 값으로 정규화해 인증 대상 경로를 기본 차단 상태로 유지합니다.
	 */
	public InternalApiKeyProperties {
		readKey = readKey == null ? "" : readKey;
		orderKey = orderKey == null ? "" : orderKey;
	}
}
