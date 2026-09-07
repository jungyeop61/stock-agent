package com.jusika.backend.ordermodification;

/** 주문 정정 미리보기의 승인과 실행 생명주기를 구분합니다. */
public enum OrderModificationPreviewStatus {
	PENDING_APPROVAL,
	APPROVED,
	EXPIRED,
	CONSUMED
}
