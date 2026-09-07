package com.jusika.backend.conditionalorder;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.jusika.backend.toss.conditionalorder.TossConditionalOrderClient;

/**
 * 모바일 앱과 에이전트가 조건 주문 목록과 상세를 읽을 HTTP 주소를 제공합니다.
 */
@RestController
@RequestMapping("/api/accounts/{accountSeq}/conditional-orders")
public class ConditionalOrderController {

	private final TossConditionalOrderClient conditionalOrderClient;

	/**
	 * 실제 주문을 만들지 않는 토스증권 조건 주문 조회 클라이언트를 전달받습니다.
	 *
	 * @param conditionalOrderClient 조건 주문 목록과 상세를 읽는 클라이언트
	 */
	public ConditionalOrderController(TossConditionalOrderClient conditionalOrderClient) {
		this.conditionalOrderClient = conditionalOrderClient;
	}

	/**
	 * 한 계좌의 진행 중 또는 종료된 조건 주문 목록을 페이지 단위로 조회합니다.
	 *
	 * @param accountSeq 계좌 목록에서 받은 계좌 식별값
	 * @param status 진행 중이면 OPEN, 종료되었으면 CLOSED
	 * @param symbol 선택 종목 코드
	 * @param cursor 선택 다음 페이지 커서
	 * @param limit 선택 페이지 크기
	 * @return 조건 주문 목록과 다음 페이지 정보
	 */
	@GetMapping
	public ConditionalOrderListResponse getConditionalOrders(
			@PathVariable long accountSeq,
			@RequestParam ConditionalOrderListStatus status,
			@RequestParam(required = false) String symbol,
			@RequestParam(required = false) String cursor,
			@RequestParam(required = false) Integer limit) {
		return conditionalOrderClient.getConditionalOrders(
				accountSeq, status, symbol, cursor, limit);
	}

	/**
	 * 계좌와 조건 주문 식별값으로 한 건의 전체 상태와 감시 조건을 조회합니다.
	 *
	 * @param accountSeq 계좌 목록에서 받은 계좌 식별값
	 * @param conditionalOrderId 토스증권 조건 주문 식별값
	 * @return 조건 주문 상세와 감시 조건
	 */
	@GetMapping("/{conditionalOrderId}")
	public ConditionalOrderDetailResponse getConditionalOrder(
			@PathVariable long accountSeq,
			@PathVariable String conditionalOrderId) {
		return conditionalOrderClient.getConditionalOrder(accountSeq, conditionalOrderId);
	}
}
