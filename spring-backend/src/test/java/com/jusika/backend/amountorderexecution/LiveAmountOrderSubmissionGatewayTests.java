package com.jusika.backend.amountorderexecution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.jusika.backend.brokersafety.BrokerExecutionMode;
import com.jusika.backend.brokersafety.BrokerMutationBlockedException;
import com.jusika.backend.brokersafety.BrokerMutationSafetyPolicy;
import com.jusika.backend.brokersafety.BrokerSafetyProperties;
import com.jusika.backend.order.AmountOrderSubmissionRequest;
import com.jusika.backend.orderpreview.OrderSide;

/**
 * 토스 주문 클라이언트 없이 미국 주식 금액 주문 LIVE 경계가 항상 차단되는지 검사합니다.
 */
class LiveAmountOrderSubmissionGatewayTests {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withUserConfiguration(게이트웨이_검사_설정.class);

	/** LIVE 기능 플래그가 꺼져 있으면 상태 변경 전 사전 검사에서 차단하는지 검사합니다. */
	@Test
	@DisplayName("LIVE 기능 플래그가 꺼져 있으면 금액 주문 사전 검사를 차단한다")
	void LIVE_기능_플래그가_꺼져_있으면_금액_주문_사전_검사를_차단한다() {
		LiveAmountOrderSubmissionGateway gateway = 게이트웨이를_만든다(false, false);

		assertThatThrownBy(gateway::requireSubmissionAvailable)
				.isInstanceOf(BrokerMutationBlockedException.class)
				.hasMessage("실제 주문 기능이 비활성화되어 있습니다.");
		assertThat(gateway.mode()).isEqualTo("LIVE");
	}

	/** 모든 설정을 열어도 어댑터 미연결 상태가 실제 금액 주문을 차단하는지 검사합니다. */
	@Test
	@DisplayName("안전 설정을 열어도 실제 금액 주문 제출을 차단한다")
	void 안전_설정을_열어도_실제_금액_주문_제출을_차단한다() {
		LiveAmountOrderSubmissionGateway gateway = 게이트웨이를_만든다(true, false);

		assertThatThrownBy(() -> gateway.submitAmountOrder(1L, 가짜_주문을_만든다()))
				.isInstanceOf(BrokerMutationBlockedException.class)
				.hasMessage("실제 주문 어댑터가 연결되어 있지 않습니다.");
	}

	/** 모든 설정을 열어도 결과 불명 금액 주문 복구가 실제 주문 경계로 진행되지 않는지 검사합니다. */
	@Test
	@DisplayName("안전 설정을 열어도 실제 금액 주문 복구를 차단한다")
	void 안전_설정을_열어도_실제_금액_주문_복구를_차단한다() {
		LiveAmountOrderSubmissionGateway gateway = 게이트웨이를_만든다(true, false);

		assertThatThrownBy(() -> gateway.recoverAmountOrder(1L, 가짜_주문을_만든다()))
				.isInstanceOf(BrokerMutationBlockedException.class)
				.hasMessage("실제 주문 어댑터가 연결되어 있지 않습니다.");
	}

	/** LIVE 게이트웨이 생성자가 중앙 안전정책만 받고 토스 클라이언트를 받지 않는지 검사합니다. */
	@Test
	@DisplayName("LIVE 금액 주문 경계는 토스 주문 클라이언트와 연결되지 않는다")
	void LIVE_금액_주문_경계는_토스_주문_클라이언트와_연결되지_않는다() {
		assertThat(LiveAmountOrderSubmissionGateway.class.getDeclaredConstructors())
				.singleElement()
				.satisfies(constructor -> assertThat(constructor.getParameterTypes())
						.containsExactly(BrokerMutationSafetyPolicy.class));
	}

	/** MOCK 모드에서는 기존 모의 금액 주문 제출 경계만 선택되는지 검사합니다. */
	@Test
	@DisplayName("MOCK 모드는 모의 금액 주문 경계만 선택한다")
	void MOCK_모드는_모의_금액_주문_경계만_선택한다() {
		contextRunner
				.withPropertyValues("jusika.broker.mode=mock")
				.run(context -> {
					assertThat(context).hasSingleBean(AmountOrderSubmissionGateway.class);
					assertThat(context.getBean(AmountOrderSubmissionGateway.class))
							.isInstanceOf(MockAmountOrderSubmissionGateway.class);
				});
	}

	/** LIVE 모드에서는 실제 호출이 차단된 LIVE 금액 주문 제출 경계만 선택되는지 검사합니다. */
	@Test
	@DisplayName("LIVE 모드는 차단된 LIVE 금액 주문 경계만 선택한다")
	void LIVE_모드는_차단된_LIVE_금액_주문_경계만_선택한다() {
		contextRunner
				.withPropertyValues(
						"jusika.broker.mode=live",
						"jusika.broker.live-enabled=false",
						"jusika.broker.kill-switch-active=true")
				.run(context -> {
					assertThat(context).hasSingleBean(AmountOrderSubmissionGateway.class);
					assertThat(context.getBean(AmountOrderSubmissionGateway.class))
							.isInstanceOf(LiveAmountOrderSubmissionGateway.class);
				});
	}

	/** 지정한 안전 플래그 조합으로 실제 네트워크 의존성이 없는 LIVE 경계를 만듭니다. */
	private LiveAmountOrderSubmissionGateway 게이트웨이를_만든다(
			boolean liveEnabled,
			boolean killSwitchActive) {
		BrokerSafetyProperties properties = new BrokerSafetyProperties(
				BrokerExecutionMode.LIVE, liveEnabled, killSwitchActive);
		return new LiveAmountOrderSubmissionGateway(new BrokerMutationSafetyPolicy(properties));
	}

	/** LIVE 경계 차단 검사에만 사용할 전송되지 않는 금액 주문 요청을 만듭니다. */
	private AmountOrderSubmissionRequest 가짜_주문을_만든다() {
		return new AmountOrderSubmissionRequest(
				"test-client-order-id",
				"AAPL",
				OrderSide.BUY,
				new BigDecimal("100"),
				false);
	}

	/** MOCK과 LIVE 금액 주문 제출 경계의 조건부 선택만 격리해 검사하는 설정입니다. */
	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(BrokerSafetyProperties.class)
	@Import({
		BrokerMutationSafetyPolicy.class,
		MockAmountOrderSubmissionGateway.class,
		LiveAmountOrderSubmissionGateway.class
	})
	static class 게이트웨이_검사_설정 {
	}
}
