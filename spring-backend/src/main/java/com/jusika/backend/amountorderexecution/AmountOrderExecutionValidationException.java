package com.jusika.backend.amountorderexecution;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 승인 뒤 최신 금액 주문 조건이 안전 규칙을 통과하지 못했음을 나타냅니다.
 */
@ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
public class AmountOrderExecutionValidationException extends RuntimeException {

	/**
	 * 금융정보를 포함하지 않은 최종 재검증 오류를 만듭니다.
	 *
	 * @param message 사용자에게 전달할 안전한 오류 설명
	 */
	public AmountOrderExecutionValidationException(String message) {
		super(message);
	}
}
