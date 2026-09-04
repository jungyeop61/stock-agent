package com.jusika.backend.commission;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 우리 서버가 사용자에게 반환하는 계좌별 시장 수수료 목록을 표현합니다.
 *
 * @param accountSeq 조회에 사용한 계좌 식별값
 * @param commissions 국내와 미국 시장의 수수료 목록
 */
public record CommissionsResponse(
		long accountSeq,
		List<CommissionItem> commissions) {

	/**
	 * 한 시장에 적용되는 매매 수수료율과 적용 기간을 표현합니다.
	 *
	 * @param marketCountry 시장 국가 코드인 KR 또는 US
	 * @param commissionRate 매매 금액에 곱하는 소수 비율 형태의 수수료율
	 * @param startDate 수수료 적용 시작일이며 해외주식 등 시작일이 없으면 null
	 * @param endDate 수수료 적용 종료일이며 무기한이면 null
	 */
	public record CommissionItem(
			String marketCountry,
			BigDecimal commissionRate,
			LocalDate startDate,
			LocalDate endDate) {
	}
}
