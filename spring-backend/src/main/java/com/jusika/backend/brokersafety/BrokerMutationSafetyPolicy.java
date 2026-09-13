package com.jusika.backend.brokersafety;

import java.util.List;
import java.util.Locale;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.jusika.backend.brokeraudit.BrokerMutationAuditOutcome;
import com.jusika.backend.brokeraudit.BrokerMutationAuditService;
import com.jusika.backend.brokeraudit.BrokerMutationAuditStage;

/**
 * 실제 증권사 주문 변경 전에 LIVE 기능 플래그와 긴급 차단 스위치를 중앙에서 검사합니다.
 */
@Service
public class BrokerMutationSafetyPolicy {

	private final BrokerSafetyProperties properties;
	private final BrokerLiveDailyOrderRiskService dailyOrderRiskService;
	private final BrokerLiveOpenOrderCapacityService openOrderCapacityService;
	private final BrokerLiveOrderRateLimitService orderRateLimitService;
	private final BrokerMutationAuditService mutationAuditService;

	/**
	 * 애플리케이션 설정에서 읽은 증권사 실행 안전값을 전달받습니다.
	 *
	 * @param properties 실행 모드, LIVE 기능 플래그와 긴급 차단 스위치 설정
	 */
	public BrokerMutationSafetyPolicy(BrokerSafetyProperties properties) {
		this.properties = properties;
		this.dailyOrderRiskService = null;
		this.openOrderCapacityService = null;
		this.orderRateLimitService = null;
		this.mutationAuditService = null;
	}

	/** 애플리케이션에서는 일일 누적 위험과 활성 주문 개수 관리자까지 함께 연결합니다. */
	@Autowired
	public BrokerMutationSafetyPolicy(
			BrokerSafetyProperties properties,
			ObjectProvider<BrokerLiveDailyOrderRiskService> dailyOrderRiskServiceProvider,
			ObjectProvider<BrokerLiveOpenOrderCapacityService> openOrderCapacityServiceProvider,
			ObjectProvider<BrokerLiveOrderRateLimitService> orderRateLimitServiceProvider,
			ObjectProvider<BrokerMutationAuditService> mutationAuditServiceProvider) {
		this.properties = properties;
		this.dailyOrderRiskService = dailyOrderRiskServiceProvider.getIfAvailable();
		this.openOrderCapacityService = openOrderCapacityServiceProvider.getIfAvailable();
		this.orderRateLimitService = orderRateLimitServiceProvider.getIfAvailable();
		this.mutationAuditService = mutationAuditServiceProvider.getIfAvailable();
	}

	/**
	 * 현재 안전 설정과 기능별 실제 어댑터 준비 상태를 민감정보 없이 반환합니다.
	 *
	 * @return 실제 주문 가능 여부와 가장 우선적인 차단 사유
	 */
	public BrokerSafetyStatusResponse getStatus() {
		List<BrokerMutationCapabilityStatus> mutationCapabilities = createCapabilityStatuses();
		boolean allAdaptersConnected = mutationCapabilities.stream()
				.allMatch(BrokerMutationCapabilityStatus::liveAdapterConnected);
		boolean accountAllowlistConfigured = !properties.allowedAccountSeqs().isEmpty();
		boolean instrumentAllowlistConfigured = !properties.allowedInstruments().isEmpty();
		boolean orderLimitsConfigured = properties.liveOrderLimits().isConfigured();
		boolean dailyOrderLimitsConfigured = properties.liveDailyOrderLimits().isConfigured();
		boolean openOrderLimitsConfigured = properties.liveOpenOrderLimits().isConfigured();
		boolean orderRateLimitsConfigured = properties.liveOrderRateLimits().isConfigured();
		BrokerSafetyBlockReason blockReason = determineBlockReason(
				allAdaptersConnected,
				accountAllowlistConfigured,
				instrumentAllowlistConfigured,
				orderLimitsConfigured,
				dailyOrderLimitsConfigured,
				openOrderLimitsConfigured,
				orderRateLimitsConfigured);
		boolean safetyGateOpen = blockReason == BrokerSafetyBlockReason.LIVE_ADAPTER_NOT_CONNECTED
				|| blockReason == BrokerSafetyBlockReason.LIVE_ACCOUNT_ALLOWLIST_EMPTY
				|| blockReason == BrokerSafetyBlockReason.LIVE_INSTRUMENT_ALLOWLIST_EMPTY
				|| blockReason == BrokerSafetyBlockReason.LIVE_ORDER_LIMITS_NOT_CONFIGURED
				|| blockReason == BrokerSafetyBlockReason.LIVE_DAILY_ORDER_LIMITS_NOT_CONFIGURED
				|| blockReason == BrokerSafetyBlockReason.LIVE_OPEN_ORDER_LIMITS_NOT_CONFIGURED
				|| blockReason == BrokerSafetyBlockReason.LIVE_ORDER_RATE_LIMITS_NOT_CONFIGURED
				|| blockReason == BrokerSafetyBlockReason.NONE;
		boolean mutationAvailable = blockReason == BrokerSafetyBlockReason.NONE;
		return new BrokerSafetyStatusResponse(
				properties.mode(),
				properties.liveEnabled(),
				properties.killSwitchActive(),
				safetyGateOpen,
				allAdaptersConnected,
				accountAllowlistConfigured,
				instrumentAllowlistConfigured,
				orderLimitsConfigured,
				dailyOrderLimitsConfigured,
				openOrderLimitsConfigured,
				orderRateLimitsConfigured,
				mutationAvailable,
				blockReason,
				mutationCapabilities);
	}

	/**
	 * 지정한 주문 변경 기능의 실제 어댑터가 호출되기 전에 전역 설정과 해당 연결 상태를 검사합니다.
	 * 기능별 준비 상태는 독립적으로 검사하며 기본 설정에서는 모든 기능이 마지막 단계에서 차단됩니다.
	 *
	 * @param capability 실제 호출 직전 검사할 주문 변경 기능
	 */
	public void requireLiveMutationAvailable(BrokerMutationCapability capability) {
		if (capability == null) {
			throw new IllegalArgumentException("확인할 주문 변경 기능이 필요합니다.");
		}
		try {
			if (properties.mode() != BrokerExecutionMode.LIVE) {
				throw new BrokerMutationBlockedException("현재 증권사 실행 모드는 MOCK입니다.");
			}
			if (!properties.liveEnabled()) {
				throw new BrokerMutationBlockedException("실제 주문 기능이 비활성화되어 있습니다.");
			}
			if (properties.killSwitchActive()) {
				throw new BrokerMutationBlockedException("긴급 주문 차단 스위치가 활성화되어 있습니다.");
			}
			if (!isLiveAdapterConnected(capability)) {
				throw new BrokerMutationBlockedException("실제 주문 어댑터가 연결되어 있지 않습니다.");
			}
			recordMutationAudit(
					capability,
					BrokerMutationAuditStage.SAFETY_GATE,
					BrokerMutationAuditOutcome.ALLOWED);
		} catch (BrokerMutationBlockedException exception) {
			recordMutationAudit(
					capability,
					BrokerMutationAuditStage.SAFETY_GATE,
					BrokerMutationAuditOutcome.BLOCKED);
			throw exception;
		}
	}

	/** 토스 변경 요청을 보내기 직전 민감정보 없는 시작 사건을 기록합니다. */
	public void recordBrokerRequestStarted(BrokerMutationCapability capability) {
		recordMutationAudit(
				capability,
				BrokerMutationAuditStage.BROKER_REQUEST,
				BrokerMutationAuditOutcome.STARTED);
	}

	/** 토스 변경 요청이 확정 성공한 사실을 민감정보 없이 기록합니다. */
	public void recordBrokerRequestSucceeded(BrokerMutationCapability capability) {
		recordMutationAudit(
				capability,
				BrokerMutationAuditStage.BROKER_REQUEST,
				BrokerMutationAuditOutcome.SUCCEEDED);
	}

	/**
	 * 토스 변경 요청의 확정 거절 또는 결과 불명 상태를 민감정보 없이 기록합니다.
	 *
	 * @param capability 호출한 주문 변경 기능
	 * @param stateUnknown 증권사 접수 여부를 확정할 수 없으면 true
	 */
	public void recordBrokerRequestFailed(
			BrokerMutationCapability capability,
			boolean stateUnknown) {
		recordMutationAudit(
				capability,
				BrokerMutationAuditStage.BROKER_REQUEST,
				stateUnknown
						? BrokerMutationAuditOutcome.UNKNOWN
						: BrokerMutationAuditOutcome.REJECTED);
	}

	/**
	 * 실제 주문에 사용할 계좌가 명시적인 허용 목록에 포함됐는지 검사합니다.
	 * 오류에는 검사한 계좌 식별값을 포함하지 않습니다.
	 *
	 * @param accountSeq 실제 주문 변경에 사용할 계좌 식별값
	 */
	public void requireLiveAccountAllowed(long accountSeq) {
		if (accountSeq <= 0) {
			throw new IllegalArgumentException("확인할 계좌 식별값은 1 이상이어야 합니다.");
		}
		if (!properties.allowedAccountSeqs().contains(accountSeq)) {
			throw new BrokerMutationBlockedException("실제 주문이 허용된 계좌가 아닙니다.");
		}
	}

	/**
	 * 실제 주문 생성·정정 종목이 시장별 명시적 허용 목록에 포함됐는지 검사합니다.
	 * 오류에는 검사한 종목 코드를 포함하지 않습니다.
	 *
	 * @param symbol 실제 주문에 사용할 종목 코드
	 * @param currency 종목 시장을 판별할 주문 통화
	 */
	public void requireLiveInstrumentAllowed(String symbol, String currency) {
		if (symbol == null || symbol.isBlank() || currency == null || currency.isBlank()) {
			throw new IllegalArgumentException("확인할 LIVE 주문 종목과 통화가 필요합니다.");
		}
		String market = switch (currency.trim().toUpperCase(Locale.ROOT)) {
			case "KRW" -> "KR";
			case "USD" -> "US";
			default -> throw new IllegalArgumentException("LIVE 종목 검사에는 KRW 또는 USD 통화가 필요합니다.");
		};
		String instrument = market + ":" + symbol.trim().toUpperCase(Locale.ROOT);
		if (!properties.allowedInstruments().contains(instrument)) {
			throw new BrokerMutationBlockedException("실제 주문이 허용된 종목이 아닙니다.");
		}
	}

	/**
	 * 실제 주문 전 현재 일반·조건 활성 주문 합계가 계좌·종목 상한 이내인지 검사합니다.
	 *
	 * @param accountSeq 실제 주문에 사용할 계좌 식별값
	 * @param symbol 생성·정정·복구할 종목 코드
	 * @param operation 새 주문 증가 여부를 구분하는 검사 유형
	 */
	public void requireLiveOpenOrderCapacity(
			long accountSeq,
			String symbol,
			BrokerOpenOrderCapacityOperation operation) {
		requireOpenOrderCapacityService().requireCapacity(accountSeq, symbol, operation);
	}

	/** 내부 실행 상태를 만들기 전에 계좌·종목의 현재 1분 주문 빈도를 검사합니다. */
	public void requireLiveOrderRateAvailable(long accountSeq, String symbol) {
		requireOrderRateLimitService().requireCanReserve(accountSeq, symbol);
	}

	/** 같은 논리 요청의 기존 예약을 인식하면서 현재 1분 주문 빈도를 검사합니다. */
	public void requireLiveOrderRateAvailable(
			long accountSeq,
			String symbol,
			String reservationKey) {
		requireOrderRateLimitService().requireCanReserve(accountSeq, symbol, reservationKey);
	}

	/** 실제 토스 호출 직전에 계좌·종목의 1분 주문 빈도를 멱등하게 예약합니다. */
	public void reserveLiveOrderRate(
			long accountSeq,
			String symbol,
			String reservationKey) {
		requireOrderRateLimitService().reserve(accountSeq, symbol, reservationKey);
	}

	/**
	 * 실행 직전 계산한 주문 수량과 주문금액이 승인된 LIVE 1회 상한 이내인지 검사합니다.
	 * 오류에는 실제 수량, 금액과 설정 상한을 포함하지 않습니다.
	 *
	 * @param riskSnapshot 최종 금융 재검증에서 계산한 주문 위험값
	 */
	public void requireLiveOrderWithinLimits(BrokerOrderRiskSnapshot riskSnapshot) {
		if (riskSnapshot == null
				|| riskSnapshot.orderAmount() == null
				|| riskSnapshot.orderAmount().signum() <= 0
				|| (riskSnapshot.quantity() != null && riskSnapshot.quantity().signum() <= 0)) {
			throw new IllegalArgumentException("LIVE 주문 한도 검사값이 올바르지 않습니다.");
		}
		BrokerLiveOrderLimitProperties limits = properties.liveOrderLimits();
		if (!limits.isConfigured()) {
			throw new BrokerMutationBlockedException("실제 주문 수량·금액 한도가 설정되어 있지 않습니다.");
		}
		if (riskSnapshot.quantity() != null
				&& riskSnapshot.quantity().compareTo(limits.maxQuantity()) > 0) {
			throw new BrokerMutationBlockedException("실제 주문 수량이 1회 안전 한도를 초과합니다.");
		}
		if (riskSnapshot.orderAmount().compareTo(
				limits.maxOrderAmount(riskSnapshot.currency())) > 0) {
			throw new BrokerMutationBlockedException("실제 주문금액이 1회 안전 한도를 초과합니다.");
		}
	}

	/**
	 * 내부 실행 상태를 만들기 전에 현재 일일 누적 위험에 새 주문을 더할 수 있는지 확인합니다.
	 *
	 * @param accountSeq 실제 주문에 사용할 계좌 식별값
	 * @param riskSnapshot 최종 금융 재검증에서 계산한 주문 위험값
	 */
	public void requireLiveDailyOrderWithinLimits(
			long accountSeq,
			BrokerOrderRiskSnapshot riskSnapshot) {
		requireDailyOrderRiskService().requireCanReserve(accountSeq, riskSnapshot);
	}

	/**
	 * 안전 복구 전에 기존 일일 예약을 인식하면서 현재 누적 한도를 사전 검사합니다.
	 *
	 * @param accountSeq 최초 주문에 사용한 계좌 식별값
	 * @param reservationKey 원문을 저장하지 않고 해시할 최초 주문 멱등성 식별값
	 * @param riskSnapshot 최초 주문과 동일하게 재구성한 위험값
	 */
	public void requireLiveDailyOrderWithinLimits(
			long accountSeq,
			String reservationKey,
			BrokerOrderRiskSnapshot riskSnapshot) {
		requireDailyOrderRiskService().requireCanReserve(
				accountSeq, reservationKey, riskSnapshot);
	}

	/**
	 * 실제 토스 호출 직전에 주문 위험을 일일 누적값에 원자적이고 멱등하게 예약합니다.
	 *
	 * @param accountSeq 실제 주문에 사용할 계좌 식별값
	 * @param reservationKey 원문을 저장하지 않고 해시할 주문별 멱등성 식별값
	 * @param riskSnapshot 최종 금융 재검증에서 계산한 주문 위험값
	 */
	public void reserveLiveDailyOrderRisk(
			long accountSeq,
			String reservationKey,
			BrokerOrderRiskSnapshot riskSnapshot) {
		requireDailyOrderRiskService().reserve(accountSeq, reservationKey, riskSnapshot);
	}

	/** 스프링이 연결한 일일 누적 위험 관리자가 없으면 안전하게 실행을 중단합니다. */
	private BrokerLiveDailyOrderRiskService requireDailyOrderRiskService() {
		if (dailyOrderRiskService == null) {
			throw new IllegalStateException("LIVE 일일 누적 주문 안전 저장소가 연결되어 있지 않습니다.");
		}
		return dailyOrderRiskService;
	}

	/** 스프링이 연결한 활성 주문 개수 관리자가 없으면 안전하게 실행을 중단합니다. */
	private BrokerLiveOpenOrderCapacityService requireOpenOrderCapacityService() {
		if (openOrderCapacityService == null) {
			throw new IllegalStateException("LIVE 활성 주문 개수 안전 관리자가 연결되어 있지 않습니다.");
		}
		return openOrderCapacityService;
	}

	/** 스프링이 연결한 LIVE 주문 빈도 관리자가 없으면 안전하게 실행을 중단합니다. */
	private BrokerLiveOrderRateLimitService requireOrderRateLimitService() {
		if (orderRateLimitService == null) {
			throw new IllegalStateException("LIVE 주문 빈도 안전 관리자가 연결되어 있지 않습니다.");
		}
		return orderRateLimitService;
	}

	/** 연결된 감사 저장소가 있을 때만 민감정보 없는 LIVE 변경 사건을 저장합니다. */
	private void recordMutationAudit(
			BrokerMutationCapability capability,
			BrokerMutationAuditStage stage,
			BrokerMutationAuditOutcome outcome) {
		if (mutationAuditService != null) {
			mutationAuditService.record(capability, stage, outcome);
		}
	}

	/** 지정한 주문 변경 기능의 실제 어댑터 연결 상태를 설정에서 확인합니다. */
	private boolean isLiveAdapterConnected(BrokerMutationCapability capability) {
		return properties.liveAdapters().isConnected(capability);
	}

	/** 모든 주문 변경 기능의 현재 어댑터 준비 상태를 설정값에서 만듭니다. */
	private List<BrokerMutationCapabilityStatus> createCapabilityStatuses() {
		return java.util.Arrays.stream(BrokerMutationCapability.values())
				.map(capability -> new BrokerMutationCapabilityStatus(
						capability,
						properties.liveAdapters().isConnected(capability)))
				.toList();
	}

	/**
	 * 실행 모드, 기능 플래그, 긴급 차단 스위치와 어댑터 순서로 차단 사유를 결정합니다.
	 *
	 * @param allAdaptersConnected 모든 주문 변경 어댑터가 준비됐는지 여부
	 * @param accountAllowlistConfigured 허용한 실제 주문 계좌가 있는지 여부
	 * @param instrumentAllowlistConfigured 허용한 실제 주문 종목이 있는지 여부
	 * @param orderLimitsConfigured 수량과 통화별 실제 주문 상한이 설정됐는지 여부
	 * @param dailyOrderLimitsConfigured 일일 누적 수량과 통화별 상한이 설정됐는지 여부
	 * @param openOrderLimitsConfigured 계좌·종목 활성 주문 개수 상한이 설정됐는지 여부
	 * @param orderRateLimitsConfigured 계좌·종목 1분 주문 빈도 상한이 설정됐는지 여부
	 * @return 실제 주문이 막힌 이유 또는 모든 검사가 통과한 NONE
	 */
	private BrokerSafetyBlockReason determineBlockReason(
			boolean allAdaptersConnected,
			boolean accountAllowlistConfigured,
			boolean instrumentAllowlistConfigured,
			boolean orderLimitsConfigured,
			boolean dailyOrderLimitsConfigured,
			boolean openOrderLimitsConfigured,
			boolean orderRateLimitsConfigured) {
		if (properties.mode() != BrokerExecutionMode.LIVE) {
			return BrokerSafetyBlockReason.MOCK_MODE;
		}
		if (!properties.liveEnabled()) {
			return BrokerSafetyBlockReason.LIVE_FEATURE_DISABLED;
		}
		if (properties.killSwitchActive()) {
			return BrokerSafetyBlockReason.KILL_SWITCH_ACTIVE;
		}
		if (!allAdaptersConnected) {
			return BrokerSafetyBlockReason.LIVE_ADAPTER_NOT_CONNECTED;
		}
		if (!accountAllowlistConfigured) {
			return BrokerSafetyBlockReason.LIVE_ACCOUNT_ALLOWLIST_EMPTY;
		}
		if (!instrumentAllowlistConfigured) {
			return BrokerSafetyBlockReason.LIVE_INSTRUMENT_ALLOWLIST_EMPTY;
		}
		if (!orderLimitsConfigured) {
			return BrokerSafetyBlockReason.LIVE_ORDER_LIMITS_NOT_CONFIGURED;
		}
		if (!dailyOrderLimitsConfigured) {
			return BrokerSafetyBlockReason.LIVE_DAILY_ORDER_LIMITS_NOT_CONFIGURED;
		}
		if (!openOrderLimitsConfigured) {
			return BrokerSafetyBlockReason.LIVE_OPEN_ORDER_LIMITS_NOT_CONFIGURED;
		}
		return orderRateLimitsConfigured
				? BrokerSafetyBlockReason.NONE
				: BrokerSafetyBlockReason.LIVE_ORDER_RATE_LIMITS_NOT_CONFIGURED;
	}
}
