package com.jusika.backend.amountorderwindow;

/**
 * 현재 시각이 미국 주식 금액 주문 접수 구간에서 어느 상태인지 표현합니다.
 */
public enum UsAmountOrderWindowStatus {
	OPEN,
	BEFORE_OPEN,
	AFTER_CUTOFF,
	MARKET_CLOSED
}
