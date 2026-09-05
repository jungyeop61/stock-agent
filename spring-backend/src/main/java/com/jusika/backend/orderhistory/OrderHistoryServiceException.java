package com.jusika.backend.orderhistory;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 토스증권 주문 이력 서버 통신 또는 응답 검증에 실패했음을 나타냅니다.
 */
@ResponseStatus(HttpStatus.BAD_GATEWAY)
public class OrderHistoryServiceException extends RuntimeException {

	/**
	 * 인증정보와 주문정보를 포함하지 않은 외부 서비스 오류를 만듭니다.
	 *
	 * @param message 사용자에게 전달할 안전한 오류 설명
	 */
	public OrderHistoryServiceException(String message) {
		super(message);
	}
}
