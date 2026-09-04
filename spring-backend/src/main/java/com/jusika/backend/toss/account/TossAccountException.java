package com.jusika.backend.toss.account;

/**
 * 토스증권 계좌 서버 호출이나 응답 검증에 실패했음을 나타냅니다.
 */
public class TossAccountException extends RuntimeException {

	/**
	 * 사용자에게 전달할 수 있는 안전한 오류 메시지로 예외를 만듭니다.
	 *
	 * @param message 계좌번호나 인증정보를 포함하지 않은 오류 설명
	 */
	public TossAccountException(String message) {
		super(message);
	}

	/**
	 * 안전한 오류 메시지와 실제 원인을 함께 보관하는 예외를 만듭니다.
	 *
	 * @param message 계좌번호나 인증정보를 포함하지 않은 오류 설명
	 * @param cause 오류의 실제 원인
	 */
	public TossAccountException(String message, Throwable cause) {
		super(message, cause);
	}
}
