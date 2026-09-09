package com.jusika.backend.brokersafety;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 네트워크 호출 없이 LIVE 기능 플래그와 긴급 차단 스위치 조합을 검사합니다.
 */
class BrokerMutationSafetyPolicyTests {

	/** 기본 MOCK 설정이 실제 주문을 차단하고 모의 모드 사유를 반환하는지 검사합니다. */
	@Test
	@DisplayName("기본 MOCK 설정은 실제 주문을 차단한다")
	void 기본_MOCK_설정은_실제_주문을_차단한다() {
		BrokerMutationSafetyPolicy policy = 정책을_만든다(BrokerExecutionMode.MOCK, false, true);

		BrokerSafetyStatusResponse status = policy.getStatus();

		assertThat(status.mode()).isEqualTo(BrokerExecutionMode.MOCK);
		assertThat(status.liveSafetyGateOpen()).isFalse();
		assertThat(status.liveAdapterConnected()).isFalse();
		assertThat(status.liveMutationAvailable()).isFalse();
		assertThat(status.blockReason()).isEqualTo(BrokerSafetyBlockReason.MOCK_MODE);
		assertThatThrownBy(policy::requireLiveMutationAvailable)
				.isInstanceOf(BrokerMutationBlockedException.class)
				.hasMessage("현재 증권사 실행 모드는 MOCK입니다.");
	}

	/** LIVE 모드만 선택하고 기능 플래그를 켜지 않으면 차단되는지 검사합니다. */
	@Test
	@DisplayName("LIVE 기능 플래그가 꺼져 있으면 실제 주문을 차단한다")
	void LIVE_기능_플래그가_꺼져_있으면_실제_주문을_차단한다() {
		BrokerMutationSafetyPolicy policy = 정책을_만든다(BrokerExecutionMode.LIVE, false, false);

		assertThat(policy.getStatus().blockReason())
				.isEqualTo(BrokerSafetyBlockReason.LIVE_FEATURE_DISABLED);
		assertThatThrownBy(policy::requireLiveMutationAvailable)
				.isInstanceOf(BrokerMutationBlockedException.class)
				.hasMessage("실제 주문 기능이 비활성화되어 있습니다.");
	}

	/** LIVE 기능을 켜도 긴급 차단 스위치가 활성화되어 있으면 차단되는지 검사합니다. */
	@Test
	@DisplayName("긴급 차단 스위치가 활성화되어 있으면 실제 주문을 차단한다")
	void 긴급_차단_스위치가_활성화되어_있으면_실제_주문을_차단한다() {
		BrokerMutationSafetyPolicy policy = 정책을_만든다(BrokerExecutionMode.LIVE, true, true);

		assertThat(policy.getStatus().blockReason())
				.isEqualTo(BrokerSafetyBlockReason.KILL_SWITCH_ACTIVE);
		assertThatThrownBy(policy::requireLiveMutationAvailable)
				.isInstanceOf(BrokerMutationBlockedException.class)
				.hasMessage("긴급 주문 차단 스위치가 활성화되어 있습니다.");
	}

	/** 세 설정을 열어도 실제 어댑터가 없으면 주문 가능 상태가 되지 않는지 검사합니다. */
	@Test
	@DisplayName("LIVE 안전 설정이 열려도 실제 어댑터가 없으면 주문을 차단한다")
	void LIVE_안전_설정이_열려도_실제_어댑터가_없으면_주문을_차단한다() {
		BrokerMutationSafetyPolicy policy = 정책을_만든다(BrokerExecutionMode.LIVE, true, false);

		BrokerSafetyStatusResponse status = policy.getStatus();

		assertThat(status.liveSafetyGateOpen()).isTrue();
		assertThat(status.liveAdapterConnected()).isFalse();
		assertThat(status.liveMutationAvailable()).isFalse();
		assertThat(status.blockReason())
				.isEqualTo(BrokerSafetyBlockReason.LIVE_ADAPTER_NOT_CONNECTED);
		assertThatThrownBy(policy::requireLiveMutationAvailable)
				.isInstanceOf(BrokerMutationBlockedException.class)
				.hasMessage("실제 주문 어댑터가 연결되어 있지 않습니다.");
	}

	/** 실행 모드가 누락된 설정 객체를 생성 단계에서 거절하는지 검사합니다. */
	@Test
	@DisplayName("실행 모드가 누락된 안전 설정을 거절한다")
	void 실행_모드가_누락된_안전_설정을_거절한다() {
		assertThatThrownBy(() -> new BrokerSafetyProperties(null, false, true))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("증권사 실행 모드 설정이 필요합니다.");
	}

	/** 지정한 세 안전 설정으로 중앙 주문 변경 정책을 만듭니다. */
	private BrokerMutationSafetyPolicy 정책을_만든다(
			BrokerExecutionMode mode,
			boolean liveEnabled,
			boolean killSwitchActive) {
		return new BrokerMutationSafetyPolicy(
				new BrokerSafetyProperties(mode, liveEnabled, killSwitchActive));
	}
}
