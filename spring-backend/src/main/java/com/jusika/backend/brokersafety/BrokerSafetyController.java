package com.jusika.backend.brokersafety;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계좌나 토큰 없이 현재 주문 변경 안전장치 상태를 조회할 HTTP 주소를 제공합니다.
 */
@RestController
@RequestMapping("/api/broker/safety")
public class BrokerSafetyController {

	private final BrokerMutationSafetyPolicy safetyPolicy;

	/**
	 * 전역 주문 변경 안전 정책을 전달받습니다.
	 *
	 * @param safetyPolicy 현재 설정 조합을 검사할 중앙 안전 정책
	 */
	public BrokerSafetyController(BrokerMutationSafetyPolicy safetyPolicy) {
		this.safetyPolicy = safetyPolicy;
	}

	/**
	 * 민감정보 없이 실제 주문 기능과 긴급 차단 스위치 상태를 반환합니다.
	 *
	 * @return 현재 실제 주문 가능 여부와 차단 사유
	 */
	@GetMapping
	public BrokerSafetyStatusResponse getSafetyStatus() {
		return safetyPolicy.getStatus();
	}
}
