package com.jusika.backend.orderhistory;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.jusika.backend.internalauth.InternalApiAuthority;
import com.jusika.backend.internalauth.RequiresInternalApiAuthority;
import com.jusika.backend.toss.orderhistory.TossOrderHistoryClient;

/**
 * 모바일 앱과 에이전트가 토스증권 주문의 처리·체결 상태를 읽을 HTTP 주소를 제공합니다.
 */
@RestController
@RequestMapping("/api/accounts/{accountSeq}/orders")
@RequiresInternalApiAuthority(InternalApiAuthority.READ)
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
	 * 한 계좌의 진행 중 또는 종료된 주문 목록을 선택 필터와 함께 조회합니다.
	 *
	 * @param accountSeq 계좌 목록에서 받은 계좌 식별값
	 * @param status 진행 중 주문은 OPEN, 종료된 주문은 CLOSED
	 * @param symbol 선택 종목 코드
	 * @param from 선택 조회 시작일
	 * @param to 선택 조회 종료일
	 * @param cursor 종료 주문의 다음 페이지 커서
	 * @param limit 종료 주문의 페이지 크기
	 * @return 주문 목록과 다음 페이지 정보
	 */
	@GetMapping
	public OrderListResponse getOrders(
			@PathVariable long accountSeq,
			@RequestParam OrderListStatus status,
			@RequestParam(required = false) String symbol,
			@RequestParam(required = false)
			@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false)
			@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) String cursor,
			@RequestParam(required = false) Integer limit) {
		return orderHistoryClient.getOrders(
				accountSeq, status, symbol, from, to, cursor, limit);
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
