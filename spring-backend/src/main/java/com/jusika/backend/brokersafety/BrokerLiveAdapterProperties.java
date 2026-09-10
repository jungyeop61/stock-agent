package com.jusika.backend.brokersafety;

/**
 * 실제 토스증권 주문 변경 어댑터의 기능별 준비 상태입니다.
 * 모든 값은 설정이 없으면 false이며 한 기능의 상태가 다른 기능을 열지 않습니다.
 *
 * @param quantityOrderSubmission 수량 주문 제출 어댑터 준비 여부
 * @param amountOrderSubmission 금액 주문 제출 어댑터 준비 여부
 * @param normalOrderCancellation 일반 주문 취소 어댑터 준비 여부
 * @param normalOrderModification 일반 주문 정정 어댑터 준비 여부
 * @param singleConditionalOrderCreation SINGLE 조건 주문 생성 어댑터 준비 여부
 * @param ocoConditionalOrderCreation OCO 조건 주문 생성 어댑터 준비 여부
 * @param otoConditionalOrderCreation OTO 조건 주문 생성 어댑터 준비 여부
 * @param conditionalOrderCancellation 조건 주문 취소 어댑터 준비 여부
 * @param conditionalOrderModification 조건 주문 정정 어댑터 준비 여부
 */
public record BrokerLiveAdapterProperties(
		boolean quantityOrderSubmission,
		boolean amountOrderSubmission,
		boolean normalOrderCancellation,
		boolean normalOrderModification,
		boolean singleConditionalOrderCreation,
		boolean ocoConditionalOrderCreation,
		boolean otoConditionalOrderCreation,
		boolean conditionalOrderCancellation,
		boolean conditionalOrderModification) {

	/** 모든 실제 주문 변경 기능이 닫힌 기본 준비 상태를 만듭니다. */
	public static BrokerLiveAdapterProperties allDisabled() {
		return new BrokerLiveAdapterProperties(
				false,
				false,
				false,
				false,
				false,
				false,
				false,
				false,
				false);
	}

	/** 지정한 주문 변경 기능의 실제 어댑터 준비 상태만 반환합니다. */
	public boolean isConnected(BrokerMutationCapability capability) {
		if (capability == null) {
			throw new IllegalArgumentException("확인할 주문 변경 기능이 필요합니다.");
		}
		return switch (capability) {
			case QUANTITY_ORDER_SUBMISSION -> quantityOrderSubmission;
			case AMOUNT_ORDER_SUBMISSION -> amountOrderSubmission;
			case NORMAL_ORDER_CANCELLATION -> normalOrderCancellation;
			case NORMAL_ORDER_MODIFICATION -> normalOrderModification;
			case SINGLE_CONDITIONAL_ORDER_CREATION -> singleConditionalOrderCreation;
			case OCO_CONDITIONAL_ORDER_CREATION -> ocoConditionalOrderCreation;
			case OTO_CONDITIONAL_ORDER_CREATION -> otoConditionalOrderCreation;
			case CONDITIONAL_ORDER_CANCELLATION -> conditionalOrderCancellation;
			case CONDITIONAL_ORDER_MODIFICATION -> conditionalOrderModification;
		};
	}
}
