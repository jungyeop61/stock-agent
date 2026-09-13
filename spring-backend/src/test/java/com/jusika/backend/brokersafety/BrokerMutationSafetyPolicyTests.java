package com.jusika.backend.brokersafety;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.jusika.backend.brokeraudit.BrokerMutationAuditOutcome;
import com.jusika.backend.brokeraudit.BrokerMutationAuditService;
import com.jusika.backend.brokeraudit.BrokerMutationAuditStage;

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
		BrokerMutationSafetyPolicy policy = new BrokerMutationSafetyPolicy(
				new BrokerSafetyProperties(
						BrokerExecutionMode.LIVE,
						true,
						false,
						모든_어댑터를_연결한다(),
						Set.of(1L),
						Set.of("KR:005930", "US:AAPL"),
						설정된_주문_한도를_만든다(),
						설정된_일일_주문_한도를_만든다(),
						설정된_활성_주문_한도를_만든다(),
						설정된_주문_빈도_한도를_만든다()));

		BrokerSafetyStatusResponse status = policy.getStatus();

		assertThat(status.liveSafetyGateOpen()).isTrue();
		assertThat(status.liveAdapterConnected()).isTrue();
		assertThat(status.liveAccountAllowlistConfigured()).isTrue();
		assertThat(status.liveInstrumentAllowlistConfigured()).isTrue();
		assertThat(status.liveOrderLimitsConfigured()).isTrue();
		assertThat(status.liveDailyOrderLimitsConfigured()).isTrue();
		assertThat(status.liveOpenOrderLimitsConfigured()).isTrue();
		assertThat(status.liveOrderRateLimitsConfigured()).isTrue();
		assertThat(status.liveUnknownIncidentHaltActive()).isFalse();
		assertThat(status.liveMutationAvailable()).isTrue();
		assertThat(status.blockReason()).isEqualTo(BrokerSafetyBlockReason.NONE);
	}

	/** 결과 불명 사고 뒤 신규 위험은 차단하되 취소 경로는 열어 두는지 검사합니다. */
	@Test
	@DisplayName("LIVE 결과 불명 사고는 신규 주문과 정정만 자동 정지한다")
	void LIVE_결과_불명_사고는_신규_주문과_정정만_자동_정지한다() {
		결과불명_감사서비스 auditService = new 결과불명_감사서비스();
		BrokerMutationSafetyPolicy policy = new BrokerMutationSafetyPolicy(
				모든_안전장치가_열린_설정을_만든다(),
				null,
				null,
				null,
				auditService);

		BrokerSafetyStatusResponse status = policy.getStatus();

		assertThat(status.liveUnknownIncidentHaltActive()).isTrue();
		assertThat(status.liveMutationAvailable()).isFalse();
		assertThat(status.blockReason())
				.isEqualTo(BrokerSafetyBlockReason.LIVE_UNKNOWN_INCIDENT_HALT_ACTIVE);
		assertThatThrownBy(() -> policy.requireLiveMutationAvailable(
				BrokerMutationCapability.QUANTITY_ORDER_SUBMISSION))
				.isInstanceOf(BrokerMutationBlockedException.class)
				.hasMessage("결과를 확인하지 못한 LIVE 주문 사고가 있어 신규 주문과 정정을 차단합니다.");
		assertThatThrownBy(() -> policy.requireLiveMutationAvailable(
				BrokerMutationCapability.NORMAL_ORDER_MODIFICATION))
				.isInstanceOf(BrokerMutationBlockedException.class);
		assertThatCode(() -> policy.requireLiveMutationAvailable(
				BrokerMutationCapability.NORMAL_ORDER_CANCELLATION))
				.doesNotThrowAnyException();
		assertThatCode(() -> policy.requireLiveMutationAvailable(
				BrokerMutationCapability.CONDITIONAL_ORDER_CANCELLATION))
				.doesNotThrowAnyException();
		assertThat(auditService.blockedCount).isEqualTo(2);
		assertThat(auditService.allowedCount).isEqualTo(2);
	}

	/** 기존 안전 설정을 모두 열어도 1분 주문 빈도 상한이 0이면 LIVE가 열리지 않는지 검사합니다. */
	@Test
	@DisplayName("LIVE 주문 빈도 한도가 설정되지 않으면 실제 주문을 차단한다")
	void LIVE_주문_빈도_한도가_설정되지_않으면_실제_주문을_차단한다() {
		BrokerMutationSafetyPolicy policy = new BrokerMutationSafetyPolicy(
				new BrokerSafetyProperties(
						BrokerExecutionMode.LIVE, true, false,
						모든_어댑터를_연결한다(), Set.of(1L), Set.of("KR:005930"),
						설정된_주문_한도를_만든다(), 설정된_일일_주문_한도를_만든다(),
						설정된_활성_주문_한도를_만든다()));

		BrokerSafetyStatusResponse status = policy.getStatus();

		assertThat(status.liveOrderRateLimitsConfigured()).isFalse();
		assertThat(status.liveMutationAvailable()).isFalse();
		assertThat(status.blockReason())
				.isEqualTo(BrokerSafetyBlockReason.LIVE_ORDER_RATE_LIMITS_NOT_CONFIGURED);
	}

	/** 수량이나 통화별 금액 상한이 0이면 LIVE 전체 상태가 열리지 않는지 검사합니다. */
	@Test
	@DisplayName("LIVE 주문 한도가 설정되지 않으면 실제 주문을 차단한다")
	void LIVE_주문_한도가_설정되지_않으면_실제_주문을_차단한다() {
		BrokerMutationSafetyPolicy policy = new BrokerMutationSafetyPolicy(
				new BrokerSafetyProperties(
						BrokerExecutionMode.LIVE,
						true,
						false,
						모든_어댑터를_연결한다(),
						Set.of(1L),
						Set.of("KR:005930"),
						BrokerLiveOrderLimitProperties.allDisabled(),
						설정된_일일_주문_한도를_만든다()));

		BrokerSafetyStatusResponse status = policy.getStatus();

		assertThat(status.liveOrderLimitsConfigured()).isFalse();
		assertThat(status.liveMutationAvailable()).isFalse();
		assertThat(status.blockReason())
				.isEqualTo(BrokerSafetyBlockReason.LIVE_ORDER_LIMITS_NOT_CONFIGURED);
	}

	/** 1회 한도까지 열어도 일일 누적 한도가 0이면 LIVE 상태가 열리지 않는지 검사합니다. */
	@Test
	@DisplayName("LIVE 일일 누적 주문 한도가 설정되지 않으면 실제 주문을 차단한다")
	void LIVE_일일_누적_주문_한도가_설정되지_않으면_실제_주문을_차단한다() {
		BrokerMutationSafetyPolicy policy = new BrokerMutationSafetyPolicy(
				new BrokerSafetyProperties(
						BrokerExecutionMode.LIVE,
						true,
						false,
						모든_어댑터를_연결한다(),
						Set.of(1L),
						Set.of("KR:005930"),
						설정된_주문_한도를_만든다(),
						BrokerLiveDailyOrderLimitProperties.allDisabled()));

		BrokerSafetyStatusResponse status = policy.getStatus();

		assertThat(status.liveOrderLimitsConfigured()).isTrue();
		assertThat(status.liveDailyOrderLimitsConfigured()).isFalse();
		assertThat(status.liveMutationAvailable()).isFalse();
		assertThat(status.blockReason())
				.isEqualTo(BrokerSafetyBlockReason.LIVE_DAILY_ORDER_LIMITS_NOT_CONFIGURED);
	}

	/** 기존 안전 설정을 모두 열어도 활성 주문 개수 상한이 0이면 LIVE가 열리지 않는지 검사합니다. */
	@Test
	@DisplayName("LIVE 활성 주문 개수 한도가 설정되지 않으면 실제 주문을 차단한다")
	void LIVE_활성_주문_개수_한도가_설정되지_않으면_실제_주문을_차단한다() {
		BrokerMutationSafetyPolicy policy = new BrokerMutationSafetyPolicy(
				new BrokerSafetyProperties(
						BrokerExecutionMode.LIVE, true, false,
						모든_어댑터를_연결한다(), Set.of(1L), Set.of("KR:005930"),
						설정된_주문_한도를_만든다(), 설정된_일일_주문_한도를_만든다()));

		BrokerSafetyStatusResponse status = policy.getStatus();

		assertThat(status.liveOpenOrderLimitsConfigured()).isFalse();
		assertThat(status.liveMutationAvailable()).isFalse();
		assertThat(status.blockReason())
				.isEqualTo(BrokerSafetyBlockReason.LIVE_OPEN_ORDER_LIMITS_NOT_CONFIGURED);
	}

	/** 수량 주문과 수량 없는 달러 금액 주문이 설정한 경계값 안에서 통과하는지 검사합니다. */
	@Test
	@DisplayName("LIVE 주문 수량과 통화별 금액이 한도 이내면 통과한다")
	void LIVE_주문_수량과_통화별_금액이_한도_이내면_통과한다() {
		BrokerMutationSafetyPolicy policy = 한도_검사용_정책을_만든다();

		assertThatCode(() -> policy.requireLiveOrderWithinLimits(
				new BrokerOrderRiskSnapshot(
						new BigDecimal("100"), new BigDecimal("1000000"), "KRW")))
				.doesNotThrowAnyException();
		assertThatCode(() -> policy.requireLiveOrderWithinLimits(
				new BrokerOrderRiskSnapshot(null, new BigDecimal("10000"), "USD")))
				.doesNotThrowAnyException();
	}

	/** 실제 수량이나 한도 숫자를 오류에 노출하지 않고 초과 주문을 차단하는지 검사합니다. */
	@Test
	@DisplayName("LIVE 주문 수량이 한도를 넘으면 민감값 없이 차단한다")
	void LIVE_주문_수량이_한도를_넘으면_민감값_없이_차단한다() {
		BrokerMutationSafetyPolicy policy = 한도_검사용_정책을_만든다();

		assertThatThrownBy(() -> policy.requireLiveOrderWithinLimits(
				new BrokerOrderRiskSnapshot(
						new BigDecimal("101"), new BigDecimal("1000"), "KRW")))
				.isInstanceOf(BrokerMutationBlockedException.class)
				.hasMessage("실제 주문 수량이 1회 안전 한도를 초과합니다.")
				.hasMessageNotContaining("101");
	}

	/** 실제 주문금액이나 설정 상한을 오류에 노출하지 않고 초과 주문을 차단하는지 검사합니다. */
	@Test
	@DisplayName("LIVE 주문금액이 통화별 한도를 넘으면 민감값 없이 차단한다")
	void LIVE_주문금액이_통화별_한도를_넘으면_민감값_없이_차단한다() {
		BrokerMutationSafetyPolicy policy = 한도_검사용_정책을_만든다();

		assertThatThrownBy(() -> policy.requireLiveOrderWithinLimits(
				new BrokerOrderRiskSnapshot(
						BigDecimal.ONE, new BigDecimal("1000001"), "KRW")))
				.isInstanceOf(BrokerMutationBlockedException.class)
				.hasMessage("실제 주문금액이 1회 안전 한도를 초과합니다.")
				.hasMessageNotContaining("1000001");
	}

	/** 누락되거나 양수가 아닌 최종 계산값은 한도 비교 전에 거절하는지 검사합니다. */
	@Test
	@DisplayName("LIVE 주문 한도 검사에는 올바른 위험값이 필요하다")
	void LIVE_주문_한도_검사에는_올바른_위험값이_필요하다() {
		BrokerMutationSafetyPolicy policy = 한도_검사용_정책을_만든다();

		assertThatThrownBy(() -> policy.requireLiveOrderWithinLimits(null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("LIVE 주문 한도 검사값이 올바르지 않습니다.");
		assertThatThrownBy(() -> policy.requireLiveOrderWithinLimits(
				new BrokerOrderRiskSnapshot(BigDecimal.ZERO, BigDecimal.ONE, "KRW")))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("LIVE 주문 한도 검사값이 올바르지 않습니다.");
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

	/** 등록된 시장과 종목 조합만 통과하고 같은 코드라도 다른 시장이면 차단하는지 검사합니다. */
	@Test
	@DisplayName("LIVE 종목 허용 목록은 시장과 종목이 모두 일치해야 통과한다")
	void LIVE_종목_허용_목록은_시장과_종목이_모두_일치해야_통과한다() {
		BrokerMutationSafetyPolicy policy = new BrokerMutationSafetyPolicy(
				new BrokerSafetyProperties(
						BrokerExecutionMode.LIVE, true, false,
						BrokerLiveAdapterProperties.allDisabled(), Set.of(1L),
						Set.of("KR:005930", "US:AAPL"),
						BrokerLiveOrderLimitProperties.allDisabled(),
						BrokerLiveDailyOrderLimitProperties.allDisabled()));

		assertThatCode(() -> policy.requireLiveInstrumentAllowed("005930", "KRW"))
				.doesNotThrowAnyException();
		assertThatCode(() -> policy.requireLiveInstrumentAllowed("aapl", "usd"))
				.doesNotThrowAnyException();
		assertThatThrownBy(() -> policy.requireLiveInstrumentAllowed("005930", "USD"))
				.isInstanceOf(BrokerMutationBlockedException.class)
				.hasMessage("실제 주문이 허용된 종목이 아닙니다.")
				.hasMessageNotContaining("005930");
	}

	/** 다른 안전 설정이 열려도 종목 목록이 비어 있으면 전체 LIVE 상태를 차단하는지 검사합니다. */
	@Test
	@DisplayName("LIVE 종목 허용 목록이 비어 있으면 실제 주문을 차단한다")
	void LIVE_종목_허용_목록이_비어_있으면_실제_주문을_차단한다() {
		BrokerMutationSafetyPolicy policy = new BrokerMutationSafetyPolicy(
				new BrokerSafetyProperties(
						BrokerExecutionMode.LIVE, true, false,
						모든_어댑터를_연결한다(), Set.of(1L), Set.of(),
						설정된_주문_한도를_만든다(), 설정된_일일_주문_한도를_만든다()));

		assertThat(policy.getStatus().liveInstrumentAllowlistConfigured()).isFalse();
		assertThat(policy.getStatus().blockReason())
				.isEqualTo(BrokerSafetyBlockReason.LIVE_INSTRUMENT_ALLOWLIST_EMPTY);
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

	/** 수량과 원화·달러 금액 상한 검사용 중앙 정책을 만듭니다. */
	private BrokerMutationSafetyPolicy 한도_검사용_정책을_만든다() {
		return new BrokerMutationSafetyPolicy(모든_안전장치가_열린_설정을_만든다());
	}

	/** 모든 설정과 어댑터가 준비된 테스트용 안전 설정을 반환합니다. */
	private BrokerSafetyProperties 모든_안전장치가_열린_설정을_만든다() {
		return new BrokerSafetyProperties(
				BrokerExecutionMode.LIVE,
				true,
				false,
				모든_어댑터를_연결한다(),
				Set.of(1L),
				Set.of("KR:005930", "US:AAPL"),
				설정된_주문_한도를_만든다(),
				설정된_일일_주문_한도를_만든다(),
				설정된_활성_주문_한도를_만든다(),
				설정된_주문_빈도_한도를_만든다());
	}

	/** 아홉 주문 변경 기능을 모두 준비된 상태로 만드는 테스트 설정을 반환합니다. */
	private BrokerLiveAdapterProperties 모든_어댑터를_연결한다() {
		return new BrokerLiveAdapterProperties(
				true, true, true, true, true, true, true, true, true);
	}

	/** 수량과 원화·달러 금액에 사용할 테스트용 양수 상한을 반환합니다. */
	private BrokerLiveOrderLimitProperties 설정된_주문_한도를_만든다() {
		return new BrokerLiveOrderLimitProperties(
				new BigDecimal("100"),
				new BigDecimal("1000000"),
				new BigDecimal("10000"));
	}

	/** 일일 수량과 원화·달러 누적 금액에 사용할 테스트용 양수 상한을 반환합니다. */
	private BrokerLiveDailyOrderLimitProperties 설정된_일일_주문_한도를_만든다() {
		return new BrokerLiveDailyOrderLimitProperties(
				new BigDecimal("1000"),
				new BigDecimal("10000000"),
				new BigDecimal("100000"));
	}

	/** 계좌 전체와 동일 종목에 사용할 테스트용 양수 활성 주문 상한을 반환합니다. */
	private BrokerLiveOpenOrderLimitProperties 설정된_활성_주문_한도를_만든다() {
		return new BrokerLiveOpenOrderLimitProperties(20, 5);
	}

	/** 계좌 전체와 동일 종목에 사용할 테스트용 양수 1분 주문 빈도 상한을 반환합니다. */
	private BrokerLiveOrderRateLimitProperties 설정된_주문_빈도_한도를_만든다() {
		return new BrokerLiveOrderRateLimitProperties(10, 3);
	}

	/** 지정한 세 안전 설정으로 중앙 주문 변경 정책을 만듭니다. */
	private BrokerMutationSafetyPolicy 정책을_만든다(
			BrokerExecutionMode mode,
			boolean liveEnabled,
			boolean killSwitchActive) {
		return new BrokerMutationSafetyPolicy(
				new BrokerSafetyProperties(mode, liveEnabled, killSwitchActive));
	}

	/** 저장된 결과 불명 사고와 정책 감사 결과를 메모리에서 재현합니다. */
	private static final class 결과불명_감사서비스 extends BrokerMutationAuditService {

		private int blockedCount;
		private int allowedCount;

		/** 실제 저장소 없이 결과 불명 상태를 제공하도록 부모 의존성을 비워 둡니다. */
		private 결과불명_감사서비스() {
			super(null, null, null);
		}

		/** 과거 결과 불명 토스 요청이 존재하는 상태를 반환합니다. */
		@Override
		public boolean hasUnknownBrokerRequest() {
			return true;
		}

		/** 안전 관문의 허용·차단 횟수만 메모리에 기록합니다. */
		@Override
		public void record(
				BrokerMutationCapability capability,
				BrokerMutationAuditStage stage,
				BrokerMutationAuditOutcome outcome) {
			if (outcome == BrokerMutationAuditOutcome.BLOCKED) {
				blockedCount++;
			}
			if (outcome == BrokerMutationAuditOutcome.ALLOWED) {
				allowedCount++;
			}
		}
	}
}
