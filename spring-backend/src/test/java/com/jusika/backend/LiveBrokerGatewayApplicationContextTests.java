package com.jusika.backend;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import com.jusika.backend.brokersafety.BrokerExecutionMode;
import com.jusika.backend.brokersafety.BrokerMutationSafetyPolicy;
import com.jusika.backend.brokersafety.BrokerSafetyBlockReason;
import com.jusika.backend.brokersafety.BrokerSafetyStatusResponse;

/**
 * LIVE 실행 모드가 아홉 실제 경계를 선택해도 중앙 안전장치가 닫혀 있는지 통합 검증합니다.
 */
@SpringBootTest(properties = {
		"jusika.toss.client-id=테스트-클라이언트-아이디",
		"jusika.toss.client-secret=테스트-클라이언트-비밀키",
		"jusika.broker.mode=live",
		"jusika.broker.live-enabled=false",
		"jusika.broker.kill-switch-active=true"
})
class LiveBrokerGatewayApplicationContextTests {

	@Autowired
	private ApplicationContext applicationContext;

	@Autowired
	private BrokerMutationSafetyPolicy safetyPolicy;

	/** LIVE 모드의 전체 경계 선택과 실제 주문 차단 상태를 함께 확인합니다. */
	@Test
	@DisplayName("LIVE 모드는 아홉 실제 경계를 선택하지만 주문 변경은 차단한다")
	void LIVE_모드는_아홉_실제_경계를_선택하지만_주문_변경은_차단한다() {
		assertThat(applicationContext.containsBean("liveOrderSubmissionGateway")).isTrue();
		assertThat(applicationContext.containsBean("liveAmountOrderSubmissionGateway")).isTrue();
		assertThat(applicationContext.containsBean("liveOrderCancellationGateway")).isTrue();
		assertThat(applicationContext.containsBean("liveOrderModificationGateway")).isTrue();
		assertThat(applicationContext.containsBean("liveSingleConditionalOrderGateway")).isTrue();
		assertThat(applicationContext.containsBean("liveOcoConditionalOrderGateway")).isTrue();
		assertThat(applicationContext.containsBean("liveOtoConditionalOrderGateway")).isTrue();
		assertThat(applicationContext.containsBean("liveConditionalOrderCancellationGateway")).isTrue();
		assertThat(applicationContext.containsBean("liveConditionalOrderModificationGateway")).isTrue();

		assertThat(applicationContext.containsBean("mockOrderSubmissionGateway")).isFalse();
		assertThat(applicationContext.containsBean("mockAmountOrderSubmissionGateway")).isFalse();
		assertThat(applicationContext.containsBean("mockOrderCancellationGateway")).isFalse();
		assertThat(applicationContext.containsBean("mockOrderModificationGateway")).isFalse();
		assertThat(applicationContext.containsBean("mockSingleConditionalOrderGateway")).isFalse();
		assertThat(applicationContext.containsBean("mockOcoConditionalOrderGateway")).isFalse();
		assertThat(applicationContext.containsBean("mockOtoConditionalOrderGateway")).isFalse();
		assertThat(applicationContext.containsBean("mockConditionalOrderCancellationGateway")).isFalse();
		assertThat(applicationContext.containsBean("mockConditionalOrderModificationGateway")).isFalse();

		BrokerSafetyStatusResponse status = safetyPolicy.getStatus();
		assertThat(status.mode()).isEqualTo(BrokerExecutionMode.LIVE);
		assertThat(status.liveEnabled()).isFalse();
		assertThat(status.killSwitchActive()).isTrue();
		assertThat(status.liveMutationAvailable()).isFalse();
		assertThat(status.blockReason()).isEqualTo(BrokerSafetyBlockReason.LIVE_FEATURE_DISABLED);
	}
}
