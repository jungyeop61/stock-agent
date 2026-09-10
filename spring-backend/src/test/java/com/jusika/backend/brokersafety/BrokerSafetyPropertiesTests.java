package com.jusika.backend.brokersafety;

import static org.assertj.core.api.Assertions.assertThat;

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
			BrokerLiveAdapterProperties liveAdapters = context
					.getBean(BrokerSafetyProperties.class)
					.liveAdapters();

			assertThat(EnumSet.allOf(BrokerMutationCapability.class))
					.allMatch(capability -> !liveAdapters.isConnected(capability));
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

	/** 기능별 LIVE 어댑터 설정 바인딩만 격리해서 검사하는 구성을 제공합니다. */
	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(BrokerSafetyProperties.class)
	static class 안전_설정_검사_구성 {
	}
}
