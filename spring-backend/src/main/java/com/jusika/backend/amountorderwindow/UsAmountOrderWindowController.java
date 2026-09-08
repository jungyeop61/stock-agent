package com.jusika.backend.amountorderwindow;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 현재 미국 주식 금액 주문 접수 가능 여부를 읽기 전용으로 조회할 HTTP 주소를 제공합니다.
 */
@RestController
@RequestMapping("/api/market/us")
public class UsAmountOrderWindowController {

	private final UsAmountOrderWindowService windowService;

	/**
	 * 금액 주문 시간 판정을 담당하는 서비스를 전달받습니다.
	 *
	 * @param windowService 미국 금액 주문 접수 시간 판정 서비스
	 */
	public UsAmountOrderWindowController(UsAmountOrderWindowService windowService) {
		this.windowService = windowService;
	}

	/**
	 * 현재 시각의 미국 금액 주문 접수 가능 여부와 판단 근거 시간을 반환합니다.
	 * 이 함수는 토스증권 주문 생성 API를 호출하지 않습니다.
	 *
	 * @return 현재 금액 주문 접수 가능 여부
	 */
	@GetMapping("/amount-order-window")
	public UsAmountOrderWindowResponse getCurrentWindow() {
		return windowService.checkCurrentWindow();
	}
}
