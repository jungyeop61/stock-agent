package com.jusika.backend.toss.asset;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.jusika.backend.holding.HoldingsResponse;
import com.jusika.backend.holding.HoldingsResponse.CurrencyAmounts;
import com.jusika.backend.holding.HoldingsResponse.HoldingItem;
import com.jusika.backend.holding.HoldingsResponse.ItemCost;
import com.jusika.backend.holding.HoldingsResponse.ItemDailyProfitLoss;
import com.jusika.backend.holding.HoldingsResponse.ItemMarketValue;
import com.jusika.backend.holding.HoldingsResponse.ItemProfitLoss;
import com.jusika.backend.holding.HoldingsResponse.OverviewDailyProfitLoss;
import com.jusika.backend.holding.HoldingsResponse.OverviewMarketValue;
import com.jusika.backend.holding.HoldingsResponse.OverviewProfitLoss;
import com.jusika.backend.toss.asset.TossHoldingsApiResponse.TossCurrencyAmounts;
import com.jusika.backend.toss.asset.TossHoldingsApiResponse.TossHoldingItem;
import com.jusika.backend.toss.asset.TossHoldingsApiResponse.TossHoldingsOverview;
import com.jusika.backend.toss.asset.TossHoldingsApiResponse.TossItemCost;
import com.jusika.backend.toss.asset.TossHoldingsApiResponse.TossItemDailyProfitLoss;
import com.jusika.backend.toss.asset.TossHoldingsApiResponse.TossItemMarketValue;
import com.jusika.backend.toss.asset.TossHoldingsApiResponse.TossItemProfitLoss;
import com.jusika.backend.toss.asset.TossHoldingsApiResponse.TossOverviewDailyProfitLoss;
import com.jusika.backend.toss.asset.TossHoldingsApiResponse.TossOverviewMarketValue;
import com.jusika.backend.toss.asset.TossHoldingsApiResponse.TossOverviewProfitLoss;
import com.jusika.backend.toss.auth.TossAccessTokenProvider;

/**
 * 토스증권 보유주식 API를 호출하고 문자열 숫자를 정확한 숫자 형식으로 변환합니다.
 */
@Component
public class TossHoldingsClient {

	private static final String ACCOUNT_HEADER = "X-Tossinvest-Account";

	private final RestClient restClient;
	private final TossAccessTokenProvider tokenProvider;

	/**
	 * 토스증권 전용 REST 클라이언트와 토큰 공급자를 전달받습니다.
	 *
	 * @param tossRestClient 토스증권 API 전용 REST 클라이언트
	 * @param tokenProvider 유효한 액세스 토큰을 제공하는 객체
	 */
	public TossHoldingsClient(RestClient tossRestClient, TossAccessTokenProvider tokenProvider) {
		this.restClient = tossRestClient;
		this.tokenProvider = tokenProvider;
	}

	/**
	 * 지정된 계좌의 전체 보유주식과 평가 요약을 조회합니다.
	 *
	 * @param accountSeq 계좌 목록 API에서 받은 계좌 식별값
	 * @return 숫자 변환을 마친 보유주식과 평가 요약
	 * @throws TossAssetException 계좌 식별값, 서버 호출 또는 응답 형식이 올바르지 않은 경우
	 */
	public HoldingsResponse getHoldings(long accountSeq) {
		validateAccountSeq(accountSeq);

		try {
			TossHoldingsApiResponse response = restClient.get()
					.uri("/api/v1/holdings")
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenProvider.getAccessToken())
					.header(ACCOUNT_HEADER, Long.toString(accountSeq))
					.retrieve()
					.body(TossHoldingsApiResponse.class);

			return convertResponse(response, accountSeq);
		} catch (RestClientResponseException exception) {
			throw new TossAssetException(
					"토스증권 보유주식 조회에 실패했습니다. HTTP 상태: " + exception.getStatusCode().value());
		} catch (TossAssetException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			throw new TossAssetException("토스증권 보유자산 서버와 통신하지 못했습니다.");
		}
	}

	/**
	 * 외부 API를 호출하기 전에 계좌 식별값이 양수인지 확인합니다.
	 *
	 * @param accountSeq 검사할 계좌 식별값
	 */
	private void validateAccountSeq(long accountSeq) {
		if (accountSeq <= 0) {
			throw new TossAssetException("계좌 식별값은 1 이상이어야 합니다.");
		}
	}

	/**
	 * 토스증권 원본 응답 전체를 우리 서버의 보유주식 응답으로 변환합니다.
	 *
	 * @param response 토스증권이 반환한 원본 응답
	 * @param accountSeq 조회에 사용한 계좌 식별값
	 * @return 모든 문자열 숫자를 변환한 보유주식 응답
	 */
	private HoldingsResponse convertResponse(TossHoldingsApiResponse response, long accountSeq) {
		TossHoldingsOverview overview = response == null ? null : response.result();
		if (overview == null
				|| overview.totalPurchaseAmount() == null
				|| overview.marketValue() == null
				|| overview.profitLoss() == null
				|| overview.dailyProfitLoss() == null
				|| overview.items() == null) {
			throw new TossAssetException("토스증권 보유주식 응답 형식이 올바르지 않습니다.");
		}

		return new HoldingsResponse(
				accountSeq,
				convertCurrencyAmounts(overview.totalPurchaseAmount()),
				convertOverviewMarketValue(overview.marketValue()),
				convertOverviewProfitLoss(overview.profitLoss()),
				convertOverviewDailyProfitLoss(overview.dailyProfitLoss()),
				overview.items().stream().map(this::convertItem).toList());
	}

	/**
	 * 통화별 문자열 금액을 계산 가능한 숫자로 변환합니다.
	 *
	 * @param amounts 토스증권의 원화와 달러 금액
	 * @return 숫자로 변환된 통화별 금액
	 */
	private CurrencyAmounts convertCurrencyAmounts(TossCurrencyAmounts amounts) {
		if (amounts == null) {
			throw new TossAssetException("토스증권 통화별 금액이 없습니다.");
		}
		return new CurrencyAmounts(
				parseRequiredDecimal(amounts.krw()),
				parseNullableDecimal(amounts.usd()));
	}

	/**
	 * 전체 비용 공제 전후 평가금액을 숫자 응답으로 변환합니다.
	 *
	 * @param marketValue 토스증권의 전체 평가금액
	 * @return 숫자로 변환된 전체 평가금액
	 */
	private OverviewMarketValue convertOverviewMarketValue(TossOverviewMarketValue marketValue) {
		if (marketValue == null) {
			throw new TossAssetException("토스증권 전체 평가금액이 없습니다.");
		}
		return new OverviewMarketValue(
				convertCurrencyAmounts(marketValue.amount()),
				convertCurrencyAmounts(marketValue.amountAfterCost()));
	}

	/**
	 * 전체 비용 공제 전후 평가손익과 수익률을 숫자 응답으로 변환합니다.
	 *
	 * @param profitLoss 토스증권의 전체 평가손익
	 * @return 숫자로 변환된 전체 평가손익
	 */
	private OverviewProfitLoss convertOverviewProfitLoss(TossOverviewProfitLoss profitLoss) {
		if (profitLoss == null) {
			throw new TossAssetException("토스증권 전체 평가손익이 없습니다.");
		}
		return new OverviewProfitLoss(
				convertCurrencyAmounts(profitLoss.amount()),
				convertCurrencyAmounts(profitLoss.amountAfterCost()),
				parseRequiredDecimal(profitLoss.rate()),
				parseRequiredDecimal(profitLoss.rateAfterCost()));
	}

	/**
	 * 전체 일간손익과 수익률을 숫자 응답으로 변환합니다.
	 *
	 * @param dailyProfitLoss 토스증권의 전체 일간손익
	 * @return 숫자로 변환된 전체 일간손익
	 */
	private OverviewDailyProfitLoss convertOverviewDailyProfitLoss(
			TossOverviewDailyProfitLoss dailyProfitLoss) {
		if (dailyProfitLoss == null) {
			throw new TossAssetException("토스증권 전체 일간손익이 없습니다.");
		}
		return new OverviewDailyProfitLoss(
				convertCurrencyAmounts(dailyProfitLoss.amount()),
				parseRequiredDecimal(dailyProfitLoss.rate()));
	}

	/**
	 * 토스증권의 개별 보유 종목을 우리 서버의 숫자 응답으로 변환합니다.
	 *
	 * @param item 토스증권이 반환한 개별 보유 종목
	 * @return 숫자 변환을 마친 개별 보유 종목
	 */
	private HoldingItem convertItem(TossHoldingItem item) {
		if (item == null
				|| isBlank(item.symbol())
				|| isBlank(item.name())
				|| isBlank(item.marketCountry())
				|| isBlank(item.currency())) {
			throw new TossAssetException("토스증권 보유 종목에 필수 값이 없습니다.");
		}

		return new HoldingItem(
				item.symbol(),
				item.name(),
				item.marketCountry(),
				item.currency(),
				parseRequiredDecimal(item.quantity()),
				parseRequiredDecimal(item.lastPrice()),
				parseRequiredDecimal(item.averagePurchasePrice()),
				convertItemMarketValue(item.marketValue()),
				convertItemProfitLoss(item.profitLoss()),
				convertItemDailyProfitLoss(item.dailyProfitLoss()),
				convertItemCost(item.cost()));
	}

	/**
	 * 개별 종목의 매입금액과 평가금액을 숫자 응답으로 변환합니다.
	 *
	 * @param marketValue 토스증권의 개별 종목 평가금액
	 * @return 숫자로 변환된 개별 종목 평가금액
	 */
	private ItemMarketValue convertItemMarketValue(TossItemMarketValue marketValue) {
		if (marketValue == null) {
			throw new TossAssetException("토스증권 종목 평가금액이 없습니다.");
		}
		return new ItemMarketValue(
				parseRequiredDecimal(marketValue.purchaseAmount()),
				parseRequiredDecimal(marketValue.amount()),
				parseRequiredDecimal(marketValue.amountAfterCost()));
	}

	/**
	 * 개별 종목의 평가손익과 수익률을 숫자 응답으로 변환합니다.
	 *
	 * @param profitLoss 토스증권의 개별 종목 평가손익
	 * @return 숫자로 변환된 개별 종목 평가손익
	 */
	private ItemProfitLoss convertItemProfitLoss(TossItemProfitLoss profitLoss) {
		if (profitLoss == null) {
			throw new TossAssetException("토스증권 종목 평가손익이 없습니다.");
		}
		return new ItemProfitLoss(
				parseRequiredDecimal(profitLoss.amount()),
				parseRequiredDecimal(profitLoss.amountAfterCost()),
				parseRequiredDecimal(profitLoss.rate()),
				parseRequiredDecimal(profitLoss.rateAfterCost()));
	}

	/**
	 * 개별 종목의 일간손익과 수익률을 숫자 응답으로 변환합니다.
	 *
	 * @param dailyProfitLoss 토스증권의 개별 종목 일간손익
	 * @return 숫자로 변환된 개별 종목 일간손익
	 */
	private ItemDailyProfitLoss convertItemDailyProfitLoss(TossItemDailyProfitLoss dailyProfitLoss) {
		if (dailyProfitLoss == null) {
			throw new TossAssetException("토스증권 종목 일간손익이 없습니다.");
		}
		return new ItemDailyProfitLoss(
				parseRequiredDecimal(dailyProfitLoss.amount()),
				parseRequiredDecimal(dailyProfitLoss.rate()));
	}

	/**
	 * 개별 종목의 예상 수수료와 선택적인 세금을 숫자 응답으로 변환합니다.
	 *
	 * @param cost 토스증권의 개별 종목 예상 비용
	 * @return 숫자로 변환된 예상 비용
	 */
	private ItemCost convertItemCost(TossItemCost cost) {
		if (cost == null) {
			throw new TossAssetException("토스증권 종목 예상 비용이 없습니다.");
		}
		return new ItemCost(
				parseRequiredDecimal(cost.commission()),
				parseNullableDecimal(cost.tax()));
	}

	/**
	 * 반드시 존재해야 하는 문자열 숫자를 BigDecimal로 변환합니다.
	 *
	 * @param value 변환할 필수 문자열 숫자
	 * @return 정확한 십진수 값
	 */
	private BigDecimal parseRequiredDecimal(String value) {
		if (isBlank(value)) {
			throw new TossAssetException("토스증권 보유주식 응답에 필수 숫자가 없습니다.");
		}
		try {
			return new BigDecimal(value);
		} catch (NumberFormatException exception) {
			throw new TossAssetException("토스증권 보유주식 응답의 숫자 형식이 올바르지 않습니다.");
		}
	}

	/**
	 * 값이 없을 수 있는 문자열 숫자는 null을 유지하고, 값이 있으면 BigDecimal로 변환합니다.
	 *
	 * @param value 변환할 선택적인 문자열 숫자
	 * @return 값이 없으면 null, 있으면 정확한 십진수 값
	 */
	private BigDecimal parseNullableDecimal(String value) {
		return value == null ? null : parseRequiredDecimal(value);
	}

	/**
	 * 문자열이 없거나 공백만 있는지 검사합니다.
	 *
	 * @param value 검사할 문자열
	 * @return 값이 비어 있으면 true
	 */
	private boolean isBlank(String value) {
		return value == null || value.isBlank();
	}
}
