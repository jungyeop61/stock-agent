package com.jusika.backend.toss.market;

/**
 * 토스증권 현재가 응답에 포함된 개별 종목의 원본 시세를 표현합니다.
 *
 * @param symbol 종목 코드
 * @param timestamp 토스증권이 시세를 기록한 시각
 * @param lastPrice 현재가 문자열
 * @param currency 가격의 통화 코드
 */
public record TossPriceItem(
		String symbol,
		String timestamp,
		String lastPrice,
		String currency) {
}
