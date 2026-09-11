package com.jusika.backend.brokersafety;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import java.util.Set;

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
		assertThatThrownBy(() -> policy.requireLiveMutationAvailable(
				BrokerMutationCapability.QUANTITY_ORDER_SUBMISSION))
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
		assertThatThrownBy(() -> policy.requireLiveMutationAvailable(
				BrokerMutationCapability.QUANTITY_ORDER_SUBMISSION))
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
		assertThatThrownBy(() -> policy.requireLiveMutationAvailable(
				BrokerMutationCapability.QUANTITY_ORDER_SUBMISSION))
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
		assertThatThrownBy(() -> policy.requireLiveMutationAvailable(
				BrokerMutationCapability.QUANTITY_ORDER_SUBMISSION))
				.isInstanceOf(BrokerMutationBlockedException.class)
				.hasMessage("실제 주문 어댑터가 연결되어 있지 않습니다.");
	}

	/** 실제 연결 전에 모든 주문 변경 기능이 빠짐없이 미연결 상태로 공개되는지 검사합니다. */
	@Test
	@DisplayName("모든 주문 변경 기능의 LIVE 어댑터가 미연결 상태다")
	void 모든_주문_변경_기능의_LIVE_어댑터가_미연결_상태다() {
		BrokerMutationSafetyPolicy policy = 정책을_만든다(BrokerExecutionMode.LIVE, true, false);

		BrokerSafetyStatusResponse status = policy.getStatus();

		assertThat(status.mutationCapabilities())
				.extracting(BrokerMutationCapabilityStatus::capability)
				.containsExactlyInAnyOrderElementsOf(EnumSet.allOf(BrokerMutationCapability.class));
		assertThat(status.mutationCapabilities())
				.allMatch(capabilityStatus -> !capabilityStatus.liveAdapterConnected());
		assertThat(status.liveAdapterConnected()).isFalse();
		assertThat(status.liveMutationAvailable()).isFalse();
	}

	/** 한 기능의 준비 상태가 true여도 다른 주문 변경 기능은 계속 차단되는지 검사합니다. */
	@Test
	@DisplayName("기능별 LIVE 어댑터 준비 상태를 독립적으로 적용한다")
	void 기능별_LIVE_어댑터_준비_상태를_독립적으로_적용한다() {
		BrokerLiveAdapterProperties liveAdapters = new BrokerLiveAdapterProperties(
				true,
				false,
				false,
				false,
				false,
				false,
				false,
				false,
				false);
		BrokerMutationSafetyPolicy policy = new BrokerMutationSafetyPolicy(
				new BrokerSafetyProperties(
						BrokerExecutionMode.LIVE,
						true,
						false,
						liveAdapters,
						Set.of(1L)));

		assertThatCode(() -> policy.requireLiveMutationAvailable(
				BrokerMutationCapability.QUANTITY_ORDER_SUBMISSION))
				.doesNotThrowAnyException();
		assertThatThrownBy(() -> policy.requireLiveMutationAvailable(
				BrokerMutationCapability.AMOUNT_ORDER_SUBMISSION))
				.isInstanceOf(BrokerMutationBlockedException.class)
				.hasMessage("실제 주문 어댑터가 연결되어 있지 않습니다.");
		assertThat(policy.getStatus().mutationCapabilities())
				.filteredOn(BrokerMutationCapabilityStatus::liveAdapterConnected)
				.extracting(BrokerMutationCapabilityStatus::capability)
				.containsExactly(BrokerMutationCapability.QUANTITY_ORDER_SUBMISSION);
	}

	/** 모든 기능과 전역 안전 설정이 열렸을 때 상태 응답의 모순이 없는지 검사합니다. */
	@Test
	@DisplayName("모든 LIVE 안전 검사가 통과하면 차단 사유가 없다")
	void 모든_LIVE_안전_검사가_통과하면_차단_사유가_없다() {
		BrokerLiveAdapterProperties liveAdapters = new BrokerLiveAdapterProperties(
				true,
				true,
				true,
				true,
				true,
				true,
				true,
				true,
				true);
		BrokerMutationSafetyPolicy policy = new BrokerMutationSafetyPolicy(
				new BrokerSafetyProperties(
						BrokerExecutionMode.LIVE,
						true,
						false,
						liveAdapters,
						Set.of(1L)));

		BrokerSafetyStatusResponse status = policy.getStatus();

		assertThat(status.liveSafetyGateOpen()).isTrue();
		assertThat(status.liveAdapterConnected()).isTrue();
		assertThat(status.liveAccountAllowlistConfigured()).isTrue();
		assertThat(status.liveMutationAvailable()).isTrue();
		assertThat(status.blockReason()).isEqualTo(BrokerSafetyBlockReason.NONE);
	}

	/** 허용 목록에 포함된 계좌만 통과하고 다른 계좌는 식별값 노출 없이 차단하는지 검사합니다. */
	@Test
	@DisplayName("LIVE 계좌 허용 목록은 등록된 계좌만 통과시킨다")
	void LIVE_계좌_허용_목록은_등록된_계좌만_통과시킨다() {
		BrokerSafetyProperties properties = new BrokerSafetyProperties(
				BrokerExecutionMode.LIVE,
				true,
				false,
				BrokerLiveAdapterProperties.allDisabled(),
				Set.of(1L));
		BrokerMutationSafetyPolicy policy = new BrokerMutationSafetyPolicy(properties);

		assertThatCode(() -> policy.requireLiveAccountAllowed(1L))
				.doesNotThrowAnyException();
		assertThatThrownBy(() -> policy.requireLiveAccountAllowed(2L))
				.isInstanceOf(BrokerMutationBlockedException.class)
				.hasMessage("실제 주문이 허용된 계좌가 아닙니다.")
				.hasMessageNotContaining("2");
	}

	/** 유효하지 않은 계좌 식별값은 허용 목록 조회 전에 잘못된 호출로 거절하는지 검사합니다. */
	@Test
	@DisplayName("LIVE 계좌 검사에는 양수 식별값이 필요하다")
	void LIVE_계좌_검사에는_양수_식별값이_필요하다() {
		BrokerMutationSafetyPolicy policy = 정책을_만든다(BrokerExecutionMode.LIVE, true, false);

		assertThatThrownBy(() -> policy.requireLiveAccountAllowed(0L))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("확인할 계좌 식별값은 1 이상이어야 합니다.");
	}

	/** 기능 종류가 누락되면 전역 설정을 검사하기 전에 잘못된 호출로 거절하는지 검사합니다. */
	@Test
	@DisplayName("확인할 주문 변경 기능이 누락되면 거절한다")
	void 확인할_주문_변경_기능이_누락되면_거절한다() {
		BrokerMutationSafetyPolicy policy = 정책을_만든다(BrokerExecutionMode.LIVE, true, false);

		assertThatThrownBy(() -> policy.requireLiveMutationAvailable(null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("확인할 주문 변경 기능이 필요합니다.");
	}

	/** 실행 모드가 누락된 설정 객체를 생성 단계에서 거절하는지 검사합니다. */
	@Test
	@DisplayName("실행 모드가 누락된 안전 설정을 거절한다")
	void 실행_모드가_누락된_안전_설정을_거절한다() {
		assertThatThrownBy(() -> new BrokerSafetyProperties(null, false, true))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("증권사 실행 모드 설정이 필요합니다.");
	}

	/** 기능별 어댑터 설정이 명시적으로 null이면 잘못된 설정으로 거절하는지 검사합니다. */
	@Test
	@DisplayName("기능별 LIVE 어댑터 설정이 누락되면 거절한다")
	void 기능별_LIVE_어댑터_설정이_누락되면_거절한다() {
		assertThatThrownBy(() -> new BrokerSafetyProperties(
				BrokerExecutionMode.LIVE,
				true,
				false,
				null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("기능별 실제 주문 어댑터 설정이 필요합니다.");
	}

	/** 계좌 허용 목록에 양수가 아닌 값이 있으면 설정 생성 단계에서 거절하는지 검사합니다. */
	@Test
	@DisplayName("LIVE 계좌 허용 목록의 잘못된 식별값을 거절한다")
	void LIVE_계좌_허용_목록의_잘못된_식별값을_거절한다() {
		assertThatThrownBy(() -> new BrokerSafetyProperties(
				BrokerExecutionMode.LIVE,
				true,
				false,
				BrokerLiveAdapterProperties.allDisabled(),
				Set.of(0L)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("계좌 허용 목록에는 1 이상의 식별값만 사용할 수 있습니다.");
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
