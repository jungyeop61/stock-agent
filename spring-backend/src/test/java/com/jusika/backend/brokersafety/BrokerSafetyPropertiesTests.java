package com.jusika.backend.brokersafety;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.EnumSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** 기능별 LIVE 어댑터 준비 상태가 안전한 기본값과 정확한 설정명으로 바인딩되는지 검사합니다. */
class BrokerSafetyPropertiesTests {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withUserConfiguration(안전_설정_검사_구성.class);

	/** 어떤 준비 상태도 지정하지 않으면 아홉 기능이 모두 false인지 검사합니다. */
	@Test
	@DisplayName("기능별 LIVE 어댑터 준비 상태는 모두 false가 기본값이다")
	void 기능별_LIVE_어댑터_준비_상태는_모두_false가_기본값이다() {
		contextRunner.run(context -> {
			BrokerSafetyProperties properties = context.getBean(BrokerSafetyProperties.class);
			BrokerLiveAdapterProperties liveAdapters = properties.liveAdapters();

			assertThat(EnumSet.allOf(BrokerMutationCapability.class))
					.allMatch(capability -> !liveAdapters.isConnected(capability));
			assertThat(properties.allowedAccountSeqs()).isEmpty();
			assertThat(properties.liveOrderLimits().maxQuantity()).isZero();
			assertThat(properties.liveOrderLimits().maxKrwOrderAmount()).isZero();
			assertThat(properties.liveOrderLimits().maxUsdOrderAmount()).isZero();
			assertThat(properties.liveOrderLimits().isConfigured()).isFalse();
			assertThat(properties.liveDailyOrderLimits().maxQuantity()).isZero();
			assertThat(properties.liveDailyOrderLimits().maxKrwOrderAmount()).isZero();
			assertThat(properties.liveDailyOrderLimits().maxUsdOrderAmount()).isZero();
			assertThat(properties.liveDailyOrderLimits().isConfigured()).isFalse();
		});
	}

	/** 서로 다른 설정값이 해당 기능에만 정확히 연결되는지 검사합니다. */
	@Test
	@DisplayName("기능별 LIVE 어댑터 설정을 서로 독립적으로 바인딩한다")
	void 기능별_LIVE_어댑터_설정을_서로_독립적으로_바인딩한다() {
		contextRunner
				.withPropertyValues(
						"jusika.broker.live-adapters.quantity-order-submission=true",
						"jusika.broker.live-adapters.normal-order-modification=true",
						"jusika.broker.live-adapters.oto-conditional-order-creation=true",
						"jusika.broker.live-adapters.conditional-order-cancellation=true")
				.run(context -> {
					BrokerLiveAdapterProperties liveAdapters = context
							.getBean(BrokerSafetyProperties.class)
							.liveAdapters();

					assertThat(EnumSet.allOf(BrokerMutationCapability.class))
							.filteredOn(liveAdapters::isConnected)
							.containsExactlyInAnyOrder(
									BrokerMutationCapability.QUANTITY_ORDER_SUBMISSION,
									BrokerMutationCapability.NORMAL_ORDER_MODIFICATION,
									BrokerMutationCapability.OTO_CONDITIONAL_ORDER_CREATION,
									BrokerMutationCapability.CONDITIONAL_ORDER_CANCELLATION);
				});
	}

	/** 쉼표로 구분한 계좌 허용 목록이 중복 없이 안전 설정에 바인딩되는지 검사합니다. */
	@Test
	@DisplayName("LIVE 계좌 허용 목록을 설정에서 바인딩한다")
	void LIVE_계좌_허용_목록을_설정에서_바인딩한다() {
		contextRunner
				.withPropertyValues("jusika.broker.allowed-account-seqs=1,2")
				.run(context -> assertThat(context
						.getBean(BrokerSafetyProperties.class)
						.allowedAccountSeqs())
						.containsExactlyInAnyOrder(1L, 2L));
	}

	/** 수량과 통화별 한도가 각 설정명에서 정확한 숫자로 바인딩되는지 검사합니다. */
	@Test
	@DisplayName("LIVE 1회 주문 수량과 통화별 금액 한도를 바인딩한다")
	void LIVE_1회_주문_수량과_통화별_금액_한도를_바인딩한다() {
		contextRunner
				.withPropertyValues(
						"jusika.broker.live-order-limits.max-quantity=25",
						"jusika.broker.live-order-limits.max-krw-order-amount=5000000",
						"jusika.broker.live-order-limits.max-usd-order-amount=3000")
				.run(context -> {
					BrokerLiveOrderLimitProperties limits = context
							.getBean(BrokerSafetyProperties.class)
							.liveOrderLimits();

					assertThat(limits.maxQuantity()).isEqualByComparingTo(new BigDecimal("25"));
					assertThat(limits.maxKrwOrderAmount())
							.isEqualByComparingTo(new BigDecimal("5000000"));
					assertThat(limits.maxUsdOrderAmount())
							.isEqualByComparingTo(new BigDecimal("3000"));
					assertThat(limits.isConfigured()).isTrue();
				});
	}

	/** 한국시간 하루 누적 수량과 통화별 금액 한도가 정확히 바인딩되는지 검사합니다. */
	@Test
	@DisplayName("LIVE 일일 누적 수량과 통화별 금액 한도를 바인딩한다")
	void LIVE_일일_누적_수량과_통화별_금액_한도를_바인딩한다() {
		contextRunner
				.withPropertyValues(
						"jusika.broker.live-daily-order-limits.max-quantity=200",
						"jusika.broker.live-daily-order-limits.max-krw-order-amount=9000000",
						"jusika.broker.live-daily-order-limits.max-usd-order-amount=7000")
				.run(context -> {
					BrokerLiveDailyOrderLimitProperties limits = context
							.getBean(BrokerSafetyProperties.class)
							.liveDailyOrderLimits();

					assertThat(limits.maxQuantity()).isEqualByComparingTo("200");
					assertThat(limits.maxKrwOrderAmount()).isEqualByComparingTo("9000000");
					assertThat(limits.maxUsdOrderAmount()).isEqualByComparingTo("7000");
					assertThat(limits.isConfigured()).isTrue();
				});
	}

	/** 기능별 LIVE 어댑터 설정 바인딩만 격리해서 검사하는 구성을 제공합니다. */
	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(BrokerSafetyProperties.class)
	static class 안전_설정_검사_구성 {
	}
}
