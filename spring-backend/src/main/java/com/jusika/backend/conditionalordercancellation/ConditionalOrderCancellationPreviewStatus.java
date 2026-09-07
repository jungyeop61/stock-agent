package com.jusika.backend.conditionalordercancellation;

/** 조건 주문 취소 미리보기가 승인과 실행 과정에서 가질 수 있는 상태입니다. */
public enum ConditionalOrderCancellationPreviewStatus {
	PENDING_APPROVAL,
	APPROVED,
	EXPIRED,
	CONSUMED
}
