package com.jusika.backend.orderhistory;

import java.time.LocalDate;
import java.util.List;

/**
 * 한 계좌의 주문 목록과 다음 페이지 정보를 표현합니다.
 *
 * @param accountSeq 조회에 사용한 계좌 식별값
 * @param listStatus 진행 중 또는 종료 주문 그룹
 * @param symbol 종목 필터이며 전체 종목이면 null
 * @param from 조회 시작일이며 전체 기간이면 null
 * @param to 조회 종료일이며 전체 기간이면 null
 * @param orders 검증과 자료형 변환을 마친 주문 목록
 * @param nextCursor 다음 페이지 커서이며 다음 페이지가 없으면 null
 * @param hasNext 다음 페이지 존재 여부
 */
public record OrderListResponse(
		long accountSeq,
		OrderListStatus listStatus,
		String symbol,
		LocalDate from,
		LocalDate to,
		List<OrderDetailResponse> orders,
		String nextCursor,
		boolean hasNext) {

	/**
	 * 로그에 주문 식별값·커서·금융값이 노출되지 않도록 안전한 설명만 반환합니다.
	 *
	 * @return 민감한 주문 값이 제거된 목록 설명
	 */
	@Override
	public String toString() {
		return "OrderListResponse[accountSeq=***, listStatus=" + listStatus
				+ ", symbol=" + symbol + ", from=" + from + ", to=" + to
				+ ", orders=***, nextCursor=***, hasNext=" + hasNext + "]";
	}
}
