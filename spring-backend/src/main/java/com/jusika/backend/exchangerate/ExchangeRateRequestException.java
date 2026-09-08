package com.jusika.backend.exchangerate;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 환율 조회에 사용할 통화 조합이 올바르지 않음을 나타냅니다.
 */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class ExchangeRateRequestException extends RuntimeException {

	/**
	 * 민감정보를 포함하지 않은 안전한 요청 오류를 만듭니다.
	 *
	 * @param message 사용자에게 전달할 수 있는 오류 설명
	 */
	public ExchangeRateRequestException(String message) {
		super(message);
	}
}
