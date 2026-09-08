package com.jusika.backend.amountorderexecution;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 요청한 금액 주문 실행 기록을 데이터베이스에서 찾을 수 없음을 나타냅니다.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class AmountOrderExecutionNotFoundException extends RuntimeException {

	/**
	 * 실행 식별값을 노출하지 않는 찾을 수 없음 오류를 만듭니다.
	 *
	 * @param message 사용자에게 전달할 안전한 오류 설명
	 */
	public AmountOrderExecutionNotFoundException(String message) {
		super(message);
	}
}
