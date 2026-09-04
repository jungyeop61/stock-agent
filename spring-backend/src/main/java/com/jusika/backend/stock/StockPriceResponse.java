package com.jusika.backend.stock;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 우리 서버가 사용자에게 반환하는 종목 현재가를 표현합니다.
 *
 * @param symbol 종목 코드
 * @param price 계산에 사용할 수 있는 숫자 형태의 현재가
 * @param currency 가격의 통화 코드
 * @param timestamp 토스증권이 시세를 기록한 시각
 */
public record StockPriceResponse(
		String symbol,
		BigDecimal price,
		String currency,
		OffsetDateTime timestamp) {
}
