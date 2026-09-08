package com.jusika.backend.conditionalordermodification;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

import com.jusika.backend.conditionalorder.ConditionalOrderConditionStatus;
import com.jusika.backend.conditionalorder.ConditionalOrderConditionType;
import com.jusika.backend.conditionalorder.ConditionalOrderMarket;
import com.jusika.backend.conditionalorder.ConditionalOrderStatus;
import com.jusika.backend.conditionalorder.ConditionalOrderType;
import com.jusika.backend.conditionalordermodification.ConditionalOrderModificationPreviewResponse.OriginalCondition;
import com.jusika.backend.conditionalordermodification.ConditionalOrderModificationPreviewResponse.RequestedCondition;
import com.jusika.backend.orderpreview.OrderSide;
import com.jusika.backend.orderpreview.OrderType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** 조건 주문 정정의 원주문 사본과 새 전체 구성을 데이터베이스에 보관합니다. */
@Entity
@Table(name = "conditional_order_modification_previews")
class ConditionalOrderModificationPreviewEntity {

	@Id @Column(name = "preview_id", length = 36) private String previewId;
	@Column(name = "created_at", nullable = false) private OffsetDateTime createdAt;
	@Column(name = "expires_at", nullable = false) private OffsetDateTime expiresAt;
	@Column(name = "account_seq", nullable = false) private long accountSeq;
	@Column(name = "original_conditional_order_id", nullable = false, length = 512)
	private String originalConditionalOrderId;
	@Enumerated(EnumType.STRING) @Column(name = "original_type", nullable = false, length = 16)
	private ConditionalOrderType originalType;
	@Enumerated(EnumType.STRING) @Column(name = "original_status", nullable = false, length = 32)
	private ConditionalOrderStatus originalStatus;
	@Column(nullable = false, length = 32) private String symbol;
	@Enumerated(EnumType.STRING) @Column(nullable = false, length = 8)
	private ConditionalOrderMarket market;
	@Column(name = "original_quantity", nullable = false, precision = 65, scale = 18)
	private BigDecimal originalQuantity;
	@Enumerated(EnumType.STRING) @Column(name = "original_order_type", nullable = false, length = 8)
	private OrderType originalOrderType;
	@Column(name = "original_expire_date") private LocalDate originalExpireDate;
	@Enumerated(EnumType.STRING) @Column(name = "original_first_type", nullable = false, length = 32)
	private ConditionalOrderConditionType originalFirstType;
	@Enumerated(EnumType.STRING) @Column(name = "original_first_status", nullable = false, length = 32)
	private ConditionalOrderConditionStatus originalFirstStatus;
	@Column(name = "original_first_trigger_price", precision = 65, scale = 18)
	private BigDecimal originalFirstTriggerPrice;
	@Column(name = "original_first_target_profit_rate", precision = 65, scale = 18)
	private BigDecimal originalFirstTargetProfitRate;
	@Column(name = "original_first_order_price", precision = 65, scale = 18)
	private BigDecimal originalFirstOrderPrice;
	@Column(name = "original_first_triggered_order_id", length = 512)
	private String originalFirstTriggeredOrderId;
	@Enumerated(EnumType.STRING) @Column(name = "original_second_type", length = 32)
	private ConditionalOrderConditionType originalSecondType;
	@Enumerated(EnumType.STRING) @Column(name = "original_second_status", length = 32)
	private ConditionalOrderConditionStatus originalSecondStatus;
	@Column(name = "original_second_trigger_price", precision = 65, scale = 18)
	private BigDecimal originalSecondTriggerPrice;
	@Column(name = "original_second_target_profit_rate", precision = 65, scale = 18)
	private BigDecimal originalSecondTargetProfitRate;
	@Column(name = "original_second_order_price", precision = 65, scale = 18)
	private BigDecimal originalSecondOrderPrice;
	@Column(name = "original_second_triggered_order_id", length = 512)
	private String originalSecondTriggeredOrderId;
	@Column(name = "original_created_at", nullable = false) private OffsetDateTime originalCreatedAt;
	@Enumerated(EnumType.STRING) @Column(name = "requested_type", nullable = false, length = 16)
	private ConditionalOrderType requestedType;
	@Column(name = "requested_quantity", nullable = false, precision = 65, scale = 18)
	private BigDecimal requestedQuantity;
	@Enumerated(EnumType.STRING) @Column(name = "requested_order_type", nullable = false, length = 8)
	private OrderType requestedOrderType;
	@Column(name = "requested_expire_date", nullable = false) private LocalDate requestedExpireDate;
	@Enumerated(EnumType.STRING) @Column(name = "requested_first_side", nullable = false, length = 8)
	private OrderSide requestedFirstSide;
	@Column(name = "requested_first_trigger_price", nullable = false, precision = 65, scale = 18)
	private BigDecimal requestedFirstTriggerPrice;
	@Column(name = "requested_first_order_price", precision = 65, scale = 18)
	private BigDecimal requestedFirstOrderPrice;
	@Enumerated(EnumType.STRING) @Column(name = "requested_second_side", length = 8)
	private OrderSide requestedSecondSide;
	@Column(name = "requested_second_trigger_price", precision = 65, scale = 18)
	private BigDecimal requestedSecondTriggerPrice;
	@Column(name = "requested_second_order_price", precision = 65, scale = 18)
	private BigDecimal requestedSecondOrderPrice;
	@Column(name = "reference_price", nullable = false, precision = 65, scale = 18)
	private BigDecimal referencePrice;
	@Column(nullable = false, length = 3) private String currency;
	@Column(name = "requires_high_value_confirmation", nullable = false)
	private boolean requiresHighValueConfirmation;
	@Enumerated(EnumType.STRING) @Column(nullable = false, length = 32)
	private ConditionalOrderModificationPreviewStatus status;
	@Column(name = "approved_at") private OffsetDateTime approvedAt;
	@Column(name = "consumed_at") private OffsetDateTime consumedAt;
	@Version @Column(nullable = false) private long version;

	/** JPA가 저장된 미리보기 행을 복원할 때 사용하는 빈 생성자입니다. */
	protected ConditionalOrderModificationPreviewEntity() {
	}

	/** API 미리보기 응답의 모든 원주문 값과 요청값을 저장 필드에 복사합니다. */
	private ConditionalOrderModificationPreviewEntity(
			ConditionalOrderModificationPreviewResponse value) {
		previewId = value.previewId(); createdAt = value.createdAt(); expiresAt = value.expiresAt();
		accountSeq = value.accountSeq(); originalConditionalOrderId = value.originalConditionalOrderId();
		originalType = value.originalType(); originalStatus = value.originalStatus(); symbol = value.symbol();
		market = value.market(); originalQuantity = value.originalQuantity();
		originalOrderType = value.originalOrderType(); originalExpireDate = value.originalExpireDate();
		copyOriginalFirst(value.originalFirst()); copyOriginalSecond(value.originalSecond());
		originalCreatedAt = value.originalCreatedAt(); requestedType = value.requestedType();
		requestedQuantity = value.requestedQuantity(); requestedOrderType = value.requestedOrderType();
		requestedExpireDate = value.requestedExpireDate(); copyRequestedFirst(value.requestedFirst());
		copyRequestedSecond(value.requestedSecond()); referencePrice = value.referencePrice();
		currency = value.currency(); requiresHighValueConfirmation = value.requiresHighValueConfirmation();
		status = value.status(); approvedAt = value.approvedAt();
	}

	/** 기존 첫 번째 감시 조건을 저장 필드에 복사합니다. */
	private void copyOriginalFirst(OriginalCondition value) {
		originalFirstType = value.type(); originalFirstStatus = value.status();
		originalFirstTriggerPrice = value.triggerPrice(); originalFirstTargetProfitRate = value.targetProfitRate();
		originalFirstOrderPrice = value.orderPrice(); originalFirstTriggeredOrderId = value.triggeredOrderId();
	}

	/** 기존 두 번째 감시 조건이 있으면 저장 필드에 복사합니다. */
	private void copyOriginalSecond(OriginalCondition value) {
		if (value == null) return;
		originalSecondType = value.type(); originalSecondStatus = value.status();
		originalSecondTriggerPrice = value.triggerPrice(); originalSecondTargetProfitRate = value.targetProfitRate();
		originalSecondOrderPrice = value.orderPrice(); originalSecondTriggeredOrderId = value.triggeredOrderId();
	}

	/** 요청한 첫 번째 감시 조건을 저장 필드에 복사합니다. */
	private void copyRequestedFirst(RequestedCondition value) {
		requestedFirstSide = value.side(); requestedFirstTriggerPrice = value.triggerPrice();
		requestedFirstOrderPrice = value.orderPrice();
	}

	/** 요청한 두 번째 감시 조건이 있으면 저장 필드에 복사합니다. */
	private void copyRequestedSecond(RequestedCondition value) {
		if (value == null) return;
		requestedSecondSide = value.side(); requestedSecondTriggerPrice = value.triggerPrice();
		requestedSecondOrderPrice = value.orderPrice();
	}

	/** 미리보기 응답을 새 데이터베이스 저장 객체로 변환합니다. */
	static ConditionalOrderModificationPreviewEntity from(
			ConditionalOrderModificationPreviewResponse value) {
		return new ConditionalOrderModificationPreviewEntity(value);
	}

	/** 데이터베이스 저장값과 현재 승인 상태를 API 응답으로 복원합니다. */
	ConditionalOrderModificationPreviewResponse toResponse() {
		return new ConditionalOrderModificationPreviewResponse(
				previewId, createdAt, expiresAt, accountSeq, originalConditionalOrderId,
				originalType, originalStatus, symbol, market, originalQuantity, originalOrderType,
				originalExpireDate, originalFirst(), originalSecond(), originalCreatedAt,
				requestedType, requestedQuantity, requestedOrderType, requestedExpireDate,
				requestedFirst(), requestedSecond(), referencePrice, currency,
				requiresHighValueConfirmation, status, approvedAt);
	}

	/** 저장 필드에서 기존 첫 번째 감시 조건을 복원합니다. */
	private OriginalCondition originalFirst() {
		return new OriginalCondition(originalFirstType, originalFirstStatus, originalFirstTriggerPrice,
				originalFirstTargetProfitRate, originalFirstOrderPrice, originalFirstTriggeredOrderId);
	}

	/** 저장 필드에서 선택적인 기존 두 번째 감시 조건을 복원합니다. */
	private OriginalCondition originalSecond() {
		return originalSecondType == null ? null : new OriginalCondition(
				originalSecondType, originalSecondStatus, originalSecondTriggerPrice,
				originalSecondTargetProfitRate, originalSecondOrderPrice, originalSecondTriggeredOrderId);
	}

	/** 저장 필드에서 요청한 첫 번째 감시 조건을 복원합니다. */
	private RequestedCondition requestedFirst() {
		return new RequestedCondition(
				requestedFirstSide, requestedFirstTriggerPrice, requestedFirstOrderPrice);
	}

	/** 저장 필드에서 선택적인 요청 두 번째 감시 조건을 복원합니다. */
	private RequestedCondition requestedSecond() {
		return requestedSecondSide == null ? null : new RequestedCondition(
				requestedSecondSide, requestedSecondTriggerPrice, requestedSecondOrderPrice);
	}
}
