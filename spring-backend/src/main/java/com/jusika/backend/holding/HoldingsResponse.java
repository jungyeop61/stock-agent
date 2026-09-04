package com.jusika.backend.holding;

import java.math.BigDecimal;
import java.util.List;

/**
 * 우리 서버가 사용자에게 반환하는 계좌의 보유주식과 평가 요약을 표현합니다.
 *
 * @param accountSeq 조회에 사용한 계좌 식별값
 * @param totalPurchaseAmount 통화별 전체 투자원금
 * @param marketValue 통화별 전체 평가금액
 * @param profitLoss 통화별 전체 평가손익과 수익률
 * @param dailyProfitLoss 오늘의 통화별 손익과 수익률
 * @param items 보유한 개별 종목 목록
 */
public record HoldingsResponse(
		long accountSeq,
		CurrencyAmounts totalPurchaseAmount,
		OverviewMarketValue marketValue,
		OverviewProfitLoss profitLoss,
		OverviewDailyProfitLoss dailyProfitLoss,
		List<HoldingItem> items) {

	/**
	 * 국내 주식과 미국 주식의 금액을 서로 다른 통화로 구분해 표현합니다.
	 *
	 * @param krw 원화로 거래되는 국내 주식 합계
	 * @param usd 달러로 거래되는 미국 주식 합계이며 해당 종목이 없으면 null
	 */
	public record CurrencyAmounts(BigDecimal krw, BigDecimal usd) {
	}

	/**
	 * 전체 보유주식의 비용 공제 전후 평가금액을 표현합니다.
	 *
	 * @param amount 비용 공제 전 평가금액
	 * @param amountAfterCost 비용 공제 후 평가금액
	 */
	public record OverviewMarketValue(
			CurrencyAmounts amount,
			CurrencyAmounts amountAfterCost) {
	}

	/**
	 * 전체 보유주식의 비용 공제 전후 평가손익과 수익률을 표현합니다.
	 *
	 * @param amount 비용 공제 전 평가손익
	 * @param amountAfterCost 비용 공제 후 평가손익
	 * @param rate 비용 공제 전 전체 수익률
	 * @param rateAfterCost 비용 공제 후 전체 수익률
	 */
	public record OverviewProfitLoss(
			CurrencyAmounts amount,
			CurrencyAmounts amountAfterCost,
			BigDecimal rate,
			BigDecimal rateAfterCost) {
	}

	/**
	 * 전체 보유주식의 오늘 손익과 수익률을 표현합니다.
	 *
	 * @param amount 통화별 오늘 손익
	 * @param rate 오늘 전체 수익률
	 */
	public record OverviewDailyProfitLoss(
			CurrencyAmounts amount,
			BigDecimal rate) {
	}

	/**
	 * 계좌가 보유한 개별 종목과 해당 종목의 평가 정보를 표현합니다.
	 *
	 * @param symbol 종목 코드
	 * @param name 종목 이름
	 * @param marketCountry 거래 시장의 국가 코드
	 * @param currency 거래 통화
	 * @param quantity 보유 수량
	 * @param lastPrice 현재가
	 * @param averagePurchasePrice 평균 매수가
	 * @param marketValue 종목 평가금액
	 * @param profitLoss 종목 평가손익과 수익률
	 * @param dailyProfitLoss 종목의 오늘 손익과 수익률
	 * @param cost 예상 수수료와 세금
	 */
	public record HoldingItem(
			String symbol,
			String name,
			String marketCountry,
			String currency,
			BigDecimal quantity,
			BigDecimal lastPrice,
			BigDecimal averagePurchasePrice,
			ItemMarketValue marketValue,
			ItemProfitLoss profitLoss,
			ItemDailyProfitLoss dailyProfitLoss,
			ItemCost cost) {
	}

	/**
	 * 개별 종목의 매입금액과 비용 공제 전후 평가금액을 표현합니다.
	 *
	 * @param purchaseAmount 매입금액
	 * @param amount 비용 공제 전 평가금액
	 * @param amountAfterCost 비용 공제 후 평가금액
	 */
	public record ItemMarketValue(
			BigDecimal purchaseAmount,
			BigDecimal amount,
			BigDecimal amountAfterCost) {
	}

	/**
	 * 개별 종목의 비용 공제 전후 평가손익과 수익률을 표현합니다.
	 *
	 * @param amount 비용 공제 전 평가손익
	 * @param amountAfterCost 비용 공제 후 평가손익
	 * @param rate 비용 공제 전 수익률
	 * @param rateAfterCost 비용 공제 후 수익률
	 */
	public record ItemProfitLoss(
			BigDecimal amount,
			BigDecimal amountAfterCost,
			BigDecimal rate,
			BigDecimal rateAfterCost) {
	}

	/**
	 * 개별 종목의 오늘 손익과 수익률을 표현합니다.
	 *
	 * @param amount 오늘 손익금액
	 * @param rate 오늘 수익률
	 */
	public record ItemDailyProfitLoss(BigDecimal amount, BigDecimal rate) {
	}

	/**
	 * 개별 종목을 매도한다고 가정했을 때 예상되는 비용을 표현합니다.
	 *
	 * @param commission 예상 수수료
	 * @param tax 예상 세금이며 부과되지 않으면 null
	 */
	public record ItemCost(BigDecimal commission, BigDecimal tax) {
	}
}
