package com.jusika.backend.toss.auth;

/**
 * 토스증권 액세스 토큰을 안전하게 발급받지 못했을 때 사용하는 예외입니다.
 */
public class TossAuthenticationException extends RuntimeException {

	/**
	 * 외부에 노출해도 되는 안전한 설명만 담아 인증 예외를 만듭니다.
	 *
	 * @param message 비밀정보를 포함하지 않은 오류 설명
	 */
	public TossAuthenticationException(String message) {
		super(message);
	}

	/**
	 * 원인이 된 예외를 보존하되 비밀정보는 메시지에 포함하지 않습니다.
	 *
	 * @param message 비밀정보를 포함하지 않은 오류 설명
	 * @param cause 원인이 된 하위 예외
	 */
	public TossAuthenticationException(String message, Throwable cause) {
		super(message, cause);
	}
}
