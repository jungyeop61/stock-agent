package com.jusika.backend.orderhistory;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 지정한 계좌에서 토스증권 주문을 찾을 수 없음을 나타냅니다.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class OrderHistoryNotFoundException extends RuntimeException {

	/**
	 * 주문 식별값을 노출하지 않는 찾을 수 없음 오류를 만듭니다.
	 *
	 * @param message 사용자에게 전달할 안전한 오류 설명
	 */
	public OrderHistoryNotFoundException(String message) {
		super(message);
	}
}
