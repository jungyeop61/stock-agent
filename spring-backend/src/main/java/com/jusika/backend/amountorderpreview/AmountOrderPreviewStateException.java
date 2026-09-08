package com.jusika.backend.amountorderpreview;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 금액 주문 미리보기가 현재 상태에서 다시 승인될 수 없음을 나타냅니다.
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class AmountOrderPreviewStateException extends RuntimeException {

	/**
	 * 민감정보가 포함되지 않은 안전한 상태 충돌 오류를 만듭니다.
	 *
	 * @param message 사용자에게 전달할 수 있는 오류 설명
	 */
	public AmountOrderPreviewStateException(String message) {
		super(message);
	}
}
