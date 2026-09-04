package com.jusika.backend.toss.asset;

import java.util.List;

/**
 * 토스증권 보유주식 API의 원본 응답을 표현합니다.
 *
 * @param result 계좌의 보유주식 요약과 종목 목록
 */
public record TossHoldingsApiResponse(TossHoldingsOverview result) {

	/**
	 * 토스증권이 반환한 전체 보유주식 요약과 종목 목록을 표현합니다.
	 *
	 * @param totalPurchaseAmount 통화별 전체 투자원금
	 * @param marketValue 통화별 전체 평가금액
	 * @param profitLoss 통화별 전체 평가손익과 수익률
	 * @param dailyProfitLoss 통화별 전체 일간손익과 수익률
	 * @param items 개별 보유 종목 목록
	 */
	public record TossHoldingsOverview(
			TossCurrencyAmounts totalPurchaseAmount,
			TossOverviewMarketValue marketValue,
			TossOverviewProfitLoss profitLoss,
			TossOverviewDailyProfitLoss dailyProfitLoss,
			List<TossHoldingItem> items) {
	}

	/**
	 * 토스증권이 문자열 숫자로 반환한 원화와 달러 금액을 표현합니다.
	 *
	 * @param krw 원화 금액
	 * @param usd 달러 금액이며 미국 주식이 없으면 null
	 */
	public record TossCurrencyAmounts(String krw, String usd) {
	}

	/**
	 * 토스증권이 반환한 비용 공제 전후 전체 평가금액을 표현합니다.
	 *
	 * @param amount 비용 공제 전 통화별 평가금액
	 * @param amountAfterCost 비용 공제 후 통화별 평가금액
	 */
	public record TossOverviewMarketValue(
			TossCurrencyAmounts amount,
			TossCurrencyAmounts amountAfterCost) {
	}

	/**
	 * 토스증권이 반환한 비용 공제 전후 전체 손익과 수익률을 표현합니다.
	 *
	 * @param amount 비용 공제 전 통화별 손익
	 * @param amountAfterCost 비용 공제 후 통화별 손익
	 * @param rate 비용 공제 전 수익률 문자열
	 * @param rateAfterCost 비용 공제 후 수익률 문자열
	 */
	public record TossOverviewProfitLoss(
			TossCurrencyAmounts amount,
			TossCurrencyAmounts amountAfterCost,
			String rate,
			String rateAfterCost) {
	}

	/**
	 * 토스증권이 반환한 전체 일간손익과 수익률을 표현합니다.
	 *
	 * @param amount 통화별 일간손익
	 * @param rate 일간 수익률 문자열
	 */
	public record TossOverviewDailyProfitLoss(
			TossCurrencyAmounts amount,
			String rate) {
	}

	/**
	 * 토스증권이 반환한 개별 보유 종목과 평가 정보를 표현합니다.
	 *
	 * @param symbol 종목 코드
	 * @param name 종목 이름
	 * @param marketCountry 거래 시장의 국가 코드
	 * @param currency 거래 통화
	 * @param quantity 보유 수량 문자열
	 * @param lastPrice 현재가 문자열
	 * @param averagePurchasePrice 평균 매수가 문자열
	 * @param marketValue 종목 평가금액
	 * @param profitLoss 종목 평가손익과 수익률
	 * @param dailyProfitLoss 종목 일간손익과 수익률
	 * @param cost 종목 예상 비용
	 */
	public record TossHoldingItem(
			String symbol,
			String name,
			String marketCountry,
			String currency,
			String quantity,
			String lastPrice,
			String averagePurchasePrice,
			TossItemMarketValue marketValue,
			TossItemProfitLoss profitLoss,
			TossItemDailyProfitLoss dailyProfitLoss,
			TossItemCost cost) {
	}

	/**
	 * 토스증권이 반환한 개별 종목의 매입금액과 평가금액을 표현합니다.
	 *
	 * @param purchaseAmount 매입금액 문자열
	 * @param amount 비용 공제 전 평가금액 문자열
	 * @param amountAfterCost 비용 공제 후 평가금액 문자열
	 */
	public record TossItemMarketValue(
			String purchaseAmount,
			String amount,
			String amountAfterCost) {
	}

	/**
	 * 토스증권이 반환한 개별 종목의 평가손익과 수익률을 표현합니다.
	 *
	 * @param amount 비용 공제 전 손익 문자열
	 * @param amountAfterCost 비용 공제 후 손익 문자열
	 * @param rate 비용 공제 전 수익률 문자열
	 * @param rateAfterCost 비용 공제 후 수익률 문자열
	 */
	public record TossItemProfitLoss(
			String amount,
			String amountAfterCost,
			String rate,
			String rateAfterCost) {
	}

	/**
	 * 토스증권이 반환한 개별 종목의 일간손익과 수익률을 표현합니다.
	 *
	 * @param amount 일간 손익금액 문자열
	 * @param rate 일간 수익률 문자열
	 */
	public record TossItemDailyProfitLoss(String amount, String rate) {
	}

	/**
	 * 토스증권이 반환한 개별 종목의 예상 수수료와 세금을 표현합니다.
	 *
	 * @param commission 예상 수수료 문자열
	 * @param tax 예상 세금 문자열이며 세금이 없으면 null
	 */
	public record TossItemCost(String commission, String tax) {
	}

	/**
	 * 원본 금융정보가 실수로 로그에 기록되지 않도록 응답 내용을 숨깁니다.
	 *
	 * @return 보유주식 상세가 제거된 응답 설명
	 */
	@Override
	public String toString() {
		return "TossHoldingsApiResponse[result=***]";
	}
}
