package com.jusika.backend.amountorderexecution;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 금액 주문의 동일 요청 결과 반환 유효시간이 지나 안전 복구할 수 없음을 나타냅니다.
 */
@ResponseStatus(HttpStatus.GONE)
public class AmountOrderRecoveryExpiredException extends RuntimeException {

	/**
	 * 금융정보를 포함하지 않은 금액 주문 복구 만료 오류를 만듭니다.
	 *
	 * @param message 사용자에게 전달할 안전한 오류 설명
	 */
	public AmountOrderRecoveryExpiredException(String message) {
		super(message);
	}
}
