package com.jusika.backend.orderhistory;

import java.util.Arrays;

/**
 * 토스증권 주문의 처리·체결 상태를 표현하며 새 상태값은 UNKNOWN으로 안전하게 보존합니다.
 */
public enum OrderStatus {
	PENDING,
	PENDING_CANCEL,
	PENDING_REPLACE,
	PARTIAL_FILLED,
	FILLED,
	CANCELED,
	REJECTED,
	CANCEL_REJECTED,
	REPLACE_REJECTED,
	REPLACED,
	UNKNOWN;

	/**
	 * 토스증권 원본 상태 코드를 알려진 상태로 변환하고 새 코드는 UNKNOWN으로 처리합니다.
	 *
	 * @param brokerStatusCode 토스증권이 반환한 원본 상태 코드
	 * @return 알려진 주문 상태이며 아직 지원하지 않는 코드이면 UNKNOWN
	 */
	public static OrderStatus fromBrokerCode(String brokerStatusCode) {
		return Arrays.stream(values())
				.filter(status -> status != UNKNOWN && status.name().equals(brokerStatusCode))
				.findFirst()
				.orElse(UNKNOWN);
	}
}
