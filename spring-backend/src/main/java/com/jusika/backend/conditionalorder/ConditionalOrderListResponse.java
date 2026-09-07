package com.jusika.backend.conditionalorder;

import java.util.List;

/**
 * 한 계좌의 조건 주문 목록과 다음 페이지 정보를 표현합니다.
 *
 * @param accountSeq 조회에 사용한 계좌 식별값
 * @param listStatus 진행 중 또는 종료된 조건 주문 그룹
 * @param symbol 종목 필터이며 전체 종목이면 null
 * @param conditionalOrders 검증과 변환을 마친 조건 주문 목록
 * @param nextCursor 다음 페이지 커서이며 마지막 페이지이면 null
 * @param hasNext 다음 페이지 존재 여부
 */
public record ConditionalOrderListResponse(
		long accountSeq,
		ConditionalOrderListStatus listStatus,
		String symbol,
		List<ConditionalOrderDetailResponse> conditionalOrders,
		String nextCursor,
		boolean hasNext) {

	/**
	 * 로그에 조건 주문과 페이지 커서가 노출되지 않도록 안전한 설명만 반환합니다.
	 *
	 * @return 민감한 조건 주문 값이 제거된 목록 설명
	 */
	@Override
	public String toString() {
		return "ConditionalOrderListResponse[accountSeq=***, listStatus=" + listStatus
				+ ", symbol=" + symbol + ", conditionalOrders=***"
				+ ", nextCursor=***, hasNext=" + hasNext + "]";
	}
}
