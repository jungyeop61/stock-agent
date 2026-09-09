package com.jusika.backend.brokersafety;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 전역 안전 설정이 실제 주문 변경을 허용하지 않음을 나타냅니다.
 */
@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
public class BrokerMutationBlockedException extends RuntimeException {

	/**
	 * 계좌나 주문 정보를 포함하지 않은 실제 주문 차단 오류를 만듭니다.
	 *
	 * @param message 사용자에게 전달할 안전한 차단 설명
	 */
	public BrokerMutationBlockedException(String message) {
		super(message);
	}
}
