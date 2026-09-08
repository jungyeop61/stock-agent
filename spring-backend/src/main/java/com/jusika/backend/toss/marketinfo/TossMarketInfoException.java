package com.jusika.backend.toss.marketinfo;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 토스증권 시장 정보 서버 호출이나 응답 검증에 실패했음을 나타냅니다.
 */
@ResponseStatus(HttpStatus.BAD_GATEWAY)
public class TossMarketInfoException extends RuntimeException {

	/**
	 * 금융정보나 인증정보를 포함하지 않은 안전한 메시지로 예외를 만듭니다.
	 *
	 * @param message 사용자에게 전달할 수 있는 오류 설명
	 */
	public TossMarketInfoException(String message) {
		super(message);
	}
}
