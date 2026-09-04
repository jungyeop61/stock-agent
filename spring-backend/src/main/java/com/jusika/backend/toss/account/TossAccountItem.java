package com.jusika.backend.toss.account;

/**
 * 토스증권 계좌 목록 응답에 포함된 개별 계좌의 원본 정보를 표현합니다.
 *
 * @param accountNo 실제 계좌번호
 * @param accountSeq 이후 계좌 관련 API 호출에 사용할 계좌 식별값
 * @param accountType 토스증권이 정의한 계좌 유형
 */
public record TossAccountItem(
		String accountNo,
		long accountSeq,
		String accountType) {

	/**
	 * 원본 계좌 객체가 로그에 기록되더라도 실제 계좌번호가 노출되지 않도록 가립니다.
	 *
	 * @return 실제 계좌번호가 제거된 계좌 설명
	 */
	@Override
	public String toString() {
		return "TossAccountItem[accountNo=***, accountSeq=" + accountSeq
				+ ", accountType=" + accountType + "]";
	}
}
