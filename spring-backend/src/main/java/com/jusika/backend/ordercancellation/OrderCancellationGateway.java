package com.jusika.backend.ordercancellation;

import com.jusika.backend.order.OrderOperationResponse;

/** 승인된 취소 요청을 모의 처리 또는 향후 실제 증권사로 전달하는 경계입니다. */
interface OrderCancellationGateway {

	/**
	 * 최신 주문 재조회, 미리보기 소비와 취소 실행권 확보 전에 취소 경계가 안전한지 확인합니다.
	 * 실제 취소 구현은 전역 LIVE 안전정책을 통과하지 못하면 여기서 즉시 차단해야 합니다.
	 */
	void requireCancellationAvailable();

	/**
	 * 계좌 식별값을 아는 실행 단계에서 취소 경계와 계좌 허용 여부를 함께 확인합니다.
	 *
	 * @param accountSeq 취소할 주문의 계좌 식별값
	 */
	default void requireCancellationAvailable(long accountSeq) {
		requireCancellationAvailable();
	}

	/** 계좌의 원주문을 취소하고 취소된 주문 식별값을 반환합니다. */
	OrderOperationResponse cancelOrder(long accountSeq, String orderId);

	/** 현재 취소 처리가 모의인지 실제인지 식별할 이름을 반환합니다. */
	String mode();
}
