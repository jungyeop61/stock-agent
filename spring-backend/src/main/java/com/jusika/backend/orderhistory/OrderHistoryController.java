package com.jusika.backend.orderhistory;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jusika.backend.toss.orderhistory.TossOrderHistoryClient;

/**
 * 모바일 앱과 에이전트가 토스증권 주문의 처리·체결 상태를 읽을 HTTP 주소를 제공합니다.
 */
@RestController
@RequestMapping("/api/accounts/{accountSeq}/orders")
public class OrderHistoryController {

	private final TossOrderHistoryClient orderHistoryClient;

	/**
	 * 읽기 전용 토스증권 주문 상세 조회 클라이언트를 전달받습니다.
	 *
	 * @param orderHistoryClient 주문 생성 없이 상세 상태만 조회하는 클라이언트
	 */
	public OrderHistoryController(TossOrderHistoryClient orderHistoryClient) {
		this.orderHistoryClient = orderHistoryClient;
	}

	/**
	 * 계좌와 주문 식별값으로 현재 주문 상태와 누적 체결 결과를 조회합니다.
	 *
	 * @param accountSeq 계좌 목록에서 받은 계좌 식별값
	 * @param orderId 토스증권이 반환한 주문 식별값
	 * @return 주문의 현재 상태와 누적 체결 정보
	 */
	@GetMapping("/{orderId}")
	public OrderDetailResponse getOrder(
			@PathVariable long accountSeq,
			@PathVariable String orderId) {
		return orderHistoryClient.getOrder(accountSeq, orderId);
	}
}
