package com.jusika.backend.ordermodification;

import com.jusika.backend.order.OrderModificationSubmissionRequest;
import com.jusika.backend.order.OrderOperationResponse;

/** 승인된 주문 정정을 모의 처리하거나 향후 실제 증권사로 전달하는 경계입니다. */
interface OrderModificationGateway {
	/**
	 * 최신 주문·현재가 재조회, 미리보기 소비와 정정 실행권 확보 전에 경계가 안전한지 확인합니다.
	 * 실제 정정 구현은 전역 LIVE 안전정책을 통과하지 못하면 여기서 즉시 차단해야 합니다.
	 */
	void requireModificationAvailable();

	/**
	 * 계좌 식별값을 아는 실행 단계에서 정정 경계와 계좌 허용 여부를 함께 확인합니다.
	 *
	 * @param accountSeq 정정할 주문의 계좌 식별값
	 */
	default void requireModificationAvailable(long accountSeq) {
		requireModificationAvailable();
	}

	/** 원주문을 검증된 새 유형·수량·가격으로 정정합니다. */
	OrderOperationResponse modifyOrder(
			long accountSeq, String originalOrderId, OrderModificationSubmissionRequest request);
	/** 현재 정정 처리가 모의인지 실제인지 구분할 이름을 반환합니다. */
	String mode();
}
