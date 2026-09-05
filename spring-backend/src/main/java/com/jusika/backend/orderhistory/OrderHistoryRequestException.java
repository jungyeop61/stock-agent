package com.jusika.backend.orderhistory;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 주문 상세 조회에 필요한 계좌 또는 주문 식별값 형식이 올바르지 않음을 나타냅니다.
 */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class OrderHistoryRequestException extends RuntimeException {

	/**
	 * 민감정보를 포함하지 않은 조회 요청 오류를 만듭니다.
	 *
	 * @param message 사용자에게 전달할 안전한 오류 설명
	 */
	public OrderHistoryRequestException(String message) {
		super(message);
	}
}
