package com.jusika.backend.account;

/**
 * 우리 서버가 사용자에게 반환하는 안전한 계좌 정보를 표현합니다.
 *
 * @param accountSeq 이후 보유주식과 주문 API에 사용할 계좌 식별값
 * @param maskedAccountNumber 앞부분을 별표로 가린 계좌번호
 * @param accountType 토스증권이 정의한 계좌 유형
 */
public record AccountResponse(
		long accountSeq,
		String maskedAccountNumber,
		String accountType) {
}
