package com.jusika.backend.brokersafety;

import java.math.BigDecimal;

import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 실제 주문 한 건에 적용할 수량과 통화별 주문금액 상한입니다.
 * 0은 한도가 아직 승인·설정되지 않아 LIVE 주문을 차단한다는 뜻입니다.
 *
 * @param maxQuantity 한 주문의 최대 주식 수량
 * @param maxKrwOrderAmount 한 주문의 최대 원화 주문금액
 * @param maxUsdOrderAmount 한 주문의 최대 달러 주문금액
 */
public record BrokerLiveOrderLimitProperties(
		@DefaultValue("0") BigDecimal maxQuantity,
		@DefaultValue("0") BigDecimal maxKrwOrderAmount,
		@DefaultValue("0") BigDecimal maxUsdOrderAmount) {

	/** 음수나 누락된 한도 설정을 애플리케이션 설정 단계에서 거절합니다. */
	public BrokerLiveOrderLimitProperties {
		if (maxQuantity == null || maxKrwOrderAmount == null || maxUsdOrderAmount == null) {
			throw new IllegalArgumentException("LIVE 주문 수량·금액 한도 설정이 필요합니다.");
		}
		if (maxQuantity.signum() < 0
				|| maxKrwOrderAmount.signum() < 0
				|| maxUsdOrderAmount.signum() < 0) {
			throw new IllegalArgumentException("LIVE 주문 수량·금액 한도는 음수일 수 없습니다.");
		}
	}

	/** 모든 한도가 미승인 상태인 기본 설정을 만듭니다. */
	public static BrokerLiveOrderLimitProperties allDisabled() {
		return new BrokerLiveOrderLimitProperties(
				BigDecimal.ZERO,
				BigDecimal.ZERO,
				BigDecimal.ZERO);
	}

	/** 수량과 두 통화의 금액 상한이 모두 양수로 설정됐는지 반환합니다. */
	public boolean isConfigured() {
		return maxQuantity.signum() > 0
				&& maxKrwOrderAmount.signum() > 0
				&& maxUsdOrderAmount.signum() > 0;
	}

	/** 지정 통화에 적용할 주문금액 상한을 반환합니다. */
	public BigDecimal maxOrderAmount(String currency) {
		return switch (currency) {
			case "KRW" -> maxKrwOrderAmount;
			case "USD" -> maxUsdOrderAmount;
			case null, default -> throw new IllegalArgumentException("지원하지 않는 LIVE 주문 통화입니다.");
		};
	}
}
