package com.jusika.backend.orderpreview;

/**
 * 주문 미리보기가 생성된 뒤 승인과 실제 주문 사이에서 가질 수 있는 상태입니다.
 */
public enum OrderPreviewStatus {
	PENDING_APPROVAL,
	APPROVED,
	EXPIRED,
	CONSUMED
}
