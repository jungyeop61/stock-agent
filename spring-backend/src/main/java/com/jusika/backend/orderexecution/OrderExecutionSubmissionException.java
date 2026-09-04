package com.jusika.backend.orderexecution;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 주문 제출 결과가 거절되거나 불확실해 자동 재시도하면 안 되는 상태를 나타냅니다.
 */
@ResponseStatus(HttpStatus.BAD_GATEWAY)
public class OrderExecutionSubmissionException extends RuntimeException {

	/**
	 * 금융정보를 포함하지 않은 주문 제출 오류를 만듭니다.
	 *
	 * @param message 사용자에게 전달할 안전한 오류 설명
	 */
	public OrderExecutionSubmissionException(String message) {
		super(message);
	}
}
