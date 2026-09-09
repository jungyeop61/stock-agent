package com.jusika.backend.internalauth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.stereotype.Service;

/**
 * 내부 API 키를 일정 시간 비교 방식으로 확인하고 읽기·주문 권한을 구분합니다.
 */
@Service
public class InternalApiKeyAuthenticationService {

	private final InternalApiKeyProperties properties;

	/**
	 * 환경변수에서 읽은 내부 API 키 설정을 전달받습니다.
	 *
	 * @param properties 읽기 키와 주문 키 설정
	 */
	public InternalApiKeyAuthenticationService(InternalApiKeyProperties properties) {
		this.properties = properties;
	}

	/**
	 * 제출한 키가 요청 경로의 최소 권한을 충족하는지 검사합니다.
	 * 주문 키는 읽기 권한도 포함하며 읽기 키는 주문 권한을 포함하지 않습니다.
	 *
	 * @param requiredAuthority 요청 경로가 요구하는 최소 권한
	 * @param suppliedKey 요청 헤더로 제출된 내부 API 키
	 * @return 비밀값을 포함하지 않은 인증과 권한 검사 결과
	 */
	public InternalApiAuthenticationResult authenticate(
			InternalApiAuthority requiredAuthority,
			String suppliedKey) {
		if (!hasRequiredConfiguration(requiredAuthority)) {
			return InternalApiAuthenticationResult.SECURITY_NOT_CONFIGURED;
		}
		if (suppliedKey == null || suppliedKey.isEmpty()) {
			return InternalApiAuthenticationResult.CREDENTIALS_MISSING;
		}
		if (constantTimeEquals(properties.orderKey(), suppliedKey)) {
			return InternalApiAuthenticationResult.ALLOWED;
		}
		if (constantTimeEquals(properties.readKey(), suppliedKey)) {
			return requiredAuthority == InternalApiAuthority.READ
					? InternalApiAuthenticationResult.ALLOWED
					: InternalApiAuthenticationResult.AUTHORITY_INSUFFICIENT;
		}
		return InternalApiAuthenticationResult.CREDENTIALS_INVALID;
	}

	/**
	 * 요구 권한을 판별할 서버 키가 실제로 설정되어 있는지 확인합니다.
	 *
	 * @param requiredAuthority 요청 경로가 요구하는 최소 권한
	 * @return 해당 권한을 인증할 서버 키가 하나 이상 설정되어 있으면 true
	 */
	private boolean hasRequiredConfiguration(InternalApiAuthority requiredAuthority) {
		if (requiredAuthority == InternalApiAuthority.ORDER) {
			return !properties.orderKey().isEmpty() && keysAreDistinctWhenBothConfigured();
		}
		return !properties.readKey().isEmpty() || !properties.orderKey().isEmpty();
	}

	/**
	 * 읽기 키를 별도로 설정했다면 주문 키와 다른 값인지 확인합니다.
	 *
	 * @return 읽기 키가 없거나 두 키가 서로 다르면 true
	 */
	private boolean keysAreDistinctWhenBothConfigured() {
		return properties.readKey().isEmpty()
				|| !constantTimeEquals(properties.readKey(), properties.orderKey());
	}

	/**
	 * 문자열 길이와 내용 차이로 인한 단순 비교 시간 노출을 줄이며 두 키를 비교합니다.
	 *
	 * @param configuredKey 서버에 설정된 내부 API 키
	 * @param suppliedKey 요청이 제출한 내부 API 키
	 * @return 두 키의 UTF-8 바이트가 같으면 true
	 */
	private boolean constantTimeEquals(String configuredKey, String suppliedKey) {
		if (configuredKey.isEmpty()) {
			return false;
		}
		return MessageDigest.isEqual(
				configuredKey.getBytes(StandardCharsets.UTF_8),
				suppliedKey.getBytes(StandardCharsets.UTF_8));
	}
}
