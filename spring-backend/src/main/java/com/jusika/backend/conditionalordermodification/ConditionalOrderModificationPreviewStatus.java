package com.jusika.backend.conditionalordermodification;

/** 조건 주문 정정 미리보기의 승인·만료·실행 사용 상태입니다. */
public enum ConditionalOrderModificationPreviewStatus {
	PENDING_APPROVAL,
	APPROVED,
	EXPIRED,
	CONSUMED
}
