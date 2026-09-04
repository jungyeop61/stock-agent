package com.jusika.backend.toss.orderinfo;

import java.util.List;

/**
 * 토스증권 매매 수수료 API의 원본 응답을 표현합니다.
 *
 * @param result 시장별 수수료 목록
 */
public record TossCommissionsApiResponse(List<TossCommissionItem> result) {

	/**
	 * 토스증권이 문자열로 반환한 시장별 수수료율과 적용 기간을 표현합니다.
	 *
	 * @param marketCountry 시장 국가 코드
	 * @param commissionRate 문자열 형태의 수수료율
	 * @param startDate 문자열 형태의 적용 시작일이며 없으면 null
	 * @param endDate 문자열 형태의 적용 종료일이며 무기한이면 null
	 */
	public record TossCommissionItem(
			String marketCountry,
			String commissionRate,
			String startDate,
			String endDate) {

		/**
		 * 원본 항목이 로그에 기록되더라도 실제 계좌 수수료 조건이 노출되지 않도록 가립니다.
		 *
		 * @return 수수료율과 적용 기간이 제거된 항목 설명
		 */
		@Override
		public String toString() {
			return "TossCommissionItem[marketCountry=" + marketCountry + ", commissionRate=***, dates=***]";
		}
	}

	/**
	 * 원본 금융정보가 실수로 로그에 기록되지 않도록 수수료 목록을 숨깁니다.
	 *
	 * @return 수수료 상세가 제거된 응답 설명
	 */
	@Override
	public String toString() {
		return "TossCommissionsApiResponse[result=***]";
	}
}
