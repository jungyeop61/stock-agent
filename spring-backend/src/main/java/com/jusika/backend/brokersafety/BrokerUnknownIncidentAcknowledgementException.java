package com.jusika.backend.brokersafety;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** 결과 불명 사고 확인 요청이 안전 규칙을 통과하지 못했음을 나타냅니다. */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class BrokerUnknownIncidentAcknowledgementException extends RuntimeException {

	/** 주문 정보가 없는 안전한 설명으로 잘못된 확인 요청을 만듭니다. */
	public BrokerUnknownIncidentAcknowledgementException(String message) {
		super(message);
	}
}
