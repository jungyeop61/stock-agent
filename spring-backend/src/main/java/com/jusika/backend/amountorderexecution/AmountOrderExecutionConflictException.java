package com.jusika.backend.amountorderexecution;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 승인되지 않았거나 이미 실행한 금액 주문 미리보기를 실행하려 했음을 나타냅니다.
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class AmountOrderExecutionConflictException extends RuntimeException {

	/**
	 * 금융정보를 포함하지 않은 금액 주문 실행 상태 오류를 만듭니다.
	 *
	 * @param message 사용자에게 전달할 안전한 오류 설명
	 */
	public AmountOrderExecutionConflictException(String message) {
		super(message);
	}
}
