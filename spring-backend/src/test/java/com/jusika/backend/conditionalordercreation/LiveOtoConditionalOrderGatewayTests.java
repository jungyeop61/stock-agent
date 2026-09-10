package com.jusika.backend.conditionalordercreation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.jusika.backend.brokersafety.BrokerExecutionMode;
import com.jusika.backend.brokersafety.BrokerMutationBlockedException;
import com.jusika.backend.brokersafety.BrokerMutationCapability;
import com.jusika.backend.brokersafety.BrokerMutationSafetyPolicy;
import com.jusika.backend.brokersafety.BrokerSafetyProperties;
import com.jusika.backend.conditionalorder.ConditionalOrderCreationResponse;
import com.jusika.backend.conditionalorder.OtoConditionalOrderSubmissionRequest;
import com.jusika.backend.orderexecution.OrderSubmissionException;
import com.jusika.backend.orderpreview.OrderSide;
import com.jusika.backend.orderpreview.OrderType;
import com.jusika.backend.toss.conditionalorder.TossConditionalOrderClient;

/** OTO LIVE 경계가 토스 클라이언트 호출 전에 차단되고 제출 결과 상태를 보존하는지 검사합니다. */
class LiveOtoConditionalOrderGatewayTests {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withUserConfiguration(게이트웨이_검사_설정.class);

	/** LIVE 기능 플래그가 꺼져 있으면 상태 변경 전 사전 검사에서 차단하는지 검사합니다. */
	@Test
	@DisplayName("LIVE 기능 플래그가 꺼져 있으면 OTO 조건 주문 사전 검사를 차단한다")
	void LIVE_기능_플래그가_꺼져_있으면_OTO_조건_주문_사전_검사를_차단한다() {
		LiveOtoConditionalOrderGateway gateway = 게이트웨이를_만든다(false, false);

		assertThatThrownBy(gateway::requireSubmissionAvailable)
				.isInstanceOf(BrokerMutationBlockedException.class)
				.hasMessage("실제 주문 기능이 비활성화되어 있습니다.");
		assertThat(gateway.mode()).isEqualTo("LIVE");
	}

	/** 모든 설정을 열어도 어댑터 미연결 상태가 실제 OTO 생성을 차단하는지 검사합니다. */
	@Test
	@DisplayName("안전 설정을 열어도 실제 OTO 조건 주문 생성을 차단한다")
	void 안전_설정을_열어도_실제_OTO_조건_주문_생성을_차단한다() {
		RecordingTossConditionalOrderClient conditionalOrderClient =
				new RecordingTossConditionalOrderClient();
		LiveOtoConditionalOrderGateway gateway = 게이트웨이를_만든다(
				true, false, conditionalOrderClient);

		assertThatThrownBy(() -> gateway.submit(1L, 가짜_조건_주문을_만든다()))
				.isInstanceOf(BrokerMutationBlockedException.class)
				.hasMessage("실제 주문 어댑터가 연결되어 있지 않습니다.");
		assertThat(conditionalOrderClient.callCount).isZero();
	}

	/** LIVE 게이트웨이가 안전정책과 토스 조건 주문 클라이언트만 연결 경계로 받는지 검사합니다. */
	@Test
	@DisplayName("LIVE OTO 조건 주문 경계는 안전정책 뒤에 토스 클라이언트를 연결한다")
	void LIVE_OTO_조건_주문_경계는_안전정책_뒤에_토스_클라이언트를_연결한다() {
		assertThat(LiveOtoConditionalOrderGateway.class.getDeclaredConstructors())
				.singleElement()
				.satisfies(constructor -> assertThat(constructor.getParameterTypes())
						.containsExactly(
								BrokerMutationSafetyPolicy.class,
								TossConditionalOrderClient.class));
	}

	/** 정책 통과 상황에서는 OTO 전체 요청과 생성 결과를 기록용 클라이언트와 주고받는지 검사합니다. */
	@Test
	@DisplayName("OTO 조건 주문을 토스 클라이언트에 전달한다")
	void OTO_조건_주문을_토스_클라이언트에_전달한다() {
		RecordingTossConditionalOrderClient conditionalOrderClient =
				new RecordingTossConditionalOrderClient();
		LiveOtoConditionalOrderGateway gateway = new LiveOtoConditionalOrderGateway(
				new AllowingSafetyPolicy(), conditionalOrderClient);
		OtoConditionalOrderSubmissionRequest request = 가짜_조건_주문을_만든다();

		ConditionalOrderCreationResponse response = gateway.submit(1L, request);

		assertThat(response).isEqualTo(conditionalOrderClient.response);
		assertThat(conditionalOrderClient.callCount).isEqualTo(1);
		assertThat(conditionalOrderClient.accountSeq).isEqualTo(1L);
		assertThat(conditionalOrderClient.request).isSameAs(request);
	}

	/** 토스 결과 불명 오류가 자동 재생성을 막는 결과 불명 상태로 유지되는지 검사합니다. */
	@Test
	@DisplayName("토스 OTO 조건 주문 결과 불명 상태를 보존한다")
	void 토스_OTO_조건_주문_결과_불명_상태를_보존한다() {
		RecordingTossConditionalOrderClient conditionalOrderClient =
				new RecordingTossConditionalOrderClient();
		conditionalOrderClient.failure =
				new OrderSubmissionException("테스트 결과 불명", true);
		LiveOtoConditionalOrderGateway gateway = new LiveOtoConditionalOrderGateway(
				new AllowingSafetyPolicy(), conditionalOrderClient);

		assertThatThrownBy(() -> gateway.submit(1L, 가짜_조건_주문을_만든다()))
				.isInstanceOfSatisfying(OrderSubmissionException.class,
						exception -> assertThat(exception.isSubmissionStateUnknown()).isTrue())
				.hasMessage("테스트 결과 불명");
	}

	/** 토스 확정 거절 오류가 결과 불명으로 확대되지 않고 유지되는지 검사합니다. */
	@Test
	@DisplayName("토스 OTO 조건 주문 확정 거절 상태를 보존한다")
	void 토스_OTO_조건_주문_확정_거절_상태를_보존한다() {
		RecordingTossConditionalOrderClient conditionalOrderClient =
				new RecordingTossConditionalOrderClient();
		conditionalOrderClient.failure =
				new OrderSubmissionException("테스트 확정 거절", false);
		LiveOtoConditionalOrderGateway gateway = new LiveOtoConditionalOrderGateway(
				new AllowingSafetyPolicy(), conditionalOrderClient);

		assertThatThrownBy(() -> gateway.submit(1L, 가짜_조건_주문을_만든다()))
				.isInstanceOfSatisfying(OrderSubmissionException.class,
						exception -> assertThat(exception.isSubmissionStateUnknown()).isFalse())
				.hasMessage("테스트 확정 거절");
	}

	/** MOCK 모드에서는 기존 모의 OTO 조건 주문 경계만 선택되는지 검사합니다. */
	@Test
	@DisplayName("MOCK 모드는 모의 OTO 조건 주문 경계만 선택한다")
	void MOCK_모드는_모의_OTO_조건_주문_경계만_선택한다() {
		contextRunner
				.withPropertyValues("jusika.broker.mode=mock")
				.run(context -> {
					assertThat(context).hasSingleBean(OtoConditionalOrderGateway.class);
					assertThat(context.getBean(OtoConditionalOrderGateway.class))
							.isInstanceOf(MockOtoConditionalOrderGateway.class);
				});
	}

	/** LIVE 모드에서는 실제 호출이 차단된 LIVE OTO 경계만 선택되는지 검사합니다. */
	@Test
	@DisplayName("LIVE 모드는 차단된 OTO 조건 주문 경계만 선택한다")
	void LIVE_모드는_차단된_OTO_조건_주문_경계만_선택한다() {
		contextRunner
				.withPropertyValues(
						"jusika.broker.mode=live",
						"jusika.broker.live-enabled=false",
						"jusika.broker.kill-switch-active=true")
				.run(context -> {
					assertThat(context).hasSingleBean(OtoConditionalOrderGateway.class);
					assertThat(context.getBean(OtoConditionalOrderGateway.class))
							.isInstanceOf(LiveOtoConditionalOrderGateway.class);
				});
	}

	/** 지정한 안전 플래그 조합으로 실제 네트워크 의존성이 없는 LIVE 경계를 만듭니다. */
	private LiveOtoConditionalOrderGateway 게이트웨이를_만든다(
			boolean liveEnabled,
			boolean killSwitchActive) {
		return 게이트웨이를_만든다(
				liveEnabled, killSwitchActive, new RecordingTossConditionalOrderClient());
	}

	/** 지정한 안전 플래그와 기록용 클라이언트로 LIVE 경계를 만듭니다. */
	private LiveOtoConditionalOrderGateway 게이트웨이를_만든다(
			boolean liveEnabled,
			boolean killSwitchActive,
			TossConditionalOrderClient conditionalOrderClient) {
		BrokerSafetyProperties properties = new BrokerSafetyProperties(
				BrokerExecutionMode.LIVE, liveEnabled, killSwitchActive);
		return new LiveOtoConditionalOrderGateway(
				new BrokerMutationSafetyPolicy(properties), conditionalOrderClient);
	}

	/** LIVE 경계 차단 검사에만 사용할 전송되지 않는 OTO 조건 주문을 만듭니다. */
	private OtoConditionalOrderSubmissionRequest 가짜_조건_주문을_만든다() {
		return new OtoConditionalOrderSubmissionRequest(
				"test-client-order-id", "005930", BigDecimal.ONE, OrderType.LIMIT,
				LocalDate.of(2026, 9, 10),
				new OtoConditionalOrderSubmissionRequest.Condition(
						OrderSide.BUY, new BigDecimal("68000"), new BigDecimal("69000")),
				new OtoConditionalOrderSubmissionRequest.Condition(
						OrderSide.SELL, new BigDecimal("79000"), new BigDecimal("80000")),
				false);
	}

	/** MOCK과 LIVE OTO 조건 주문 경계의 조건부 선택만 격리해 검사하는 설정입니다. */
	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(BrokerSafetyProperties.class)
	@Import({
		BrokerMutationSafetyPolicy.class,
		MockOtoConditionalOrderGateway.class,
		LiveOtoConditionalOrderGateway.class
	})
	static class 게이트웨이_검사_설정 {

		/** LIVE 빈 생성에 필요하지만 실제 네트워크를 사용하지 않는 기록용 클라이언트를 제공합니다. */
		@Bean
		TossConditionalOrderClient 기록용_토스_조건_주문_클라이언트() {
			return new RecordingTossConditionalOrderClient();
		}
	}

	/** 실제 REST 의존성 없이 OTO 생성 호출 인수와 횟수만 기록합니다. */
	private static final class RecordingTossConditionalOrderClient
			extends TossConditionalOrderClient {
		private final ConditionalOrderCreationResponse response =
				new ConditionalOrderCreationResponse(
						"test-conditional-order-id", "test-client-order-id");
		private int callCount;
		private long accountSeq;
		private OtoConditionalOrderSubmissionRequest request;
		private OrderSubmissionException failure;

		/** 실제 REST 클라이언트와 토큰 공급자 없이 기록용 객체를 초기화합니다. */
		private RecordingTossConditionalOrderClient() {
			super(null, null);
		}

		/** 네트워크 호출 없이 OTO 생성 요청을 기록하고 준비된 응답 또는 오류를 반환합니다. */
		@Override
		public ConditionalOrderCreationResponse createOtoConditionalOrder(
				long accountSeq,
				OtoConditionalOrderSubmissionRequest request) {
			callCount++;
			this.accountSeq = accountSeq;
			this.request = request;
			if (failure != null) {
				throw failure;
			}
			return response;
		}
	}

	/** 기록용 클라이언트 위임만 검사할 때 중앙 안전정책 통과를 재현합니다. */
	private static final class AllowingSafetyPolicy extends BrokerMutationSafetyPolicy {

		/** 실제 설정을 열지 않고 테스트 전용 정책 객체를 초기화합니다. */
		private AllowingSafetyPolicy() {
			super(new BrokerSafetyProperties(BrokerExecutionMode.LIVE, true, false));
		}

		/** 테스트에서만 안전정책 통과 상황을 재현하며 운영 설정에는 영향을 주지 않습니다. */
		@Override
		public void requireLiveMutationAvailable(BrokerMutationCapability capability) {
			assertThat(capability)
					.isEqualTo(BrokerMutationCapability.OTO_CONDITIONAL_ORDER_CREATION);
		}
	}
}
