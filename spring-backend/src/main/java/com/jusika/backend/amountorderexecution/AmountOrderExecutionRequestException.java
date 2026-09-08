package com.jusika.backend.amountorderexecution;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 금액 주문 실행 기록 조회에 사용한 식별값 형식이 올바르지 않음을 나타냅니다.
 */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class AmountOrderExecutionRequestException extends RuntimeException {

	/**
	 * 식별값을 노출하지 않는 안전한 요청 오류를 만듭니다.
	 *
	 * @param message 사용자에게 전달할 안전한 오류 설명
	 */
	public AmountOrderExecutionRequestException(String message) {
		super(message);
	}
}
