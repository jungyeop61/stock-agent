package com.jusika.backend.orderexecution;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 승인 후 계좌 상태를 다시 확인했을 때 주문 조건을 통과하지 못했음을 나타냅니다.
 */
@ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
public class OrderExecutionValidationException extends RuntimeException {

	/**
	 * 금융정보를 포함하지 않은 최종 재검증 오류를 만듭니다.
	 *
	 * @param message 사용자에게 전달할 안전한 오류 설명
	 */
	public OrderExecutionValidationException(String message) {
		super(message);
	}
}
