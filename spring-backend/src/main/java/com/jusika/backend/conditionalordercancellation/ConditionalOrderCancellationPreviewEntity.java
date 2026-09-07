package com.jusika.backend.conditionalordercancellation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

import com.jusika.backend.conditionalorder.ConditionalOrderConditionStatus;
import com.jusika.backend.conditionalorder.ConditionalOrderConditionType;
import com.jusika.backend.conditionalorder.ConditionalOrderMarket;
import com.jusika.backend.conditionalorder.ConditionalOrderStatus;
import com.jusika.backend.conditionalorder.ConditionalOrderType;
import com.jusika.backend.conditionalordercancellation.ConditionalOrderCancellationPreviewResponse.ConditionSnapshot;
import com.jusika.backend.orderpreview.OrderType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** 사용자가 확인할 조건 주문 취소 대상의 변경 불가 사본을 데이터베이스에 보관합니다. */
@Entity
@Table(name = "conditional_order_cancellation_previews")
class ConditionalOrderCancellationPreviewEntity {

	@Id
	@Column(name = "preview_id", nullable = false, length = 36)
	private String previewId;

	@Column(name = "created_at", nullable = false)
	private OffsetDateTime createdAt;

	@Column(name = "expires_at", nullable = false)
	private OffsetDateTime expiresAt;

	@Column(name = "account_seq", nullable = false)
	private long accountSeq;

	@Column(name = "conditional_order_id", nullable = false, length = 512)
	private String conditionalOrderId;

	@Enumerated(EnumType.STRING)
	@Column(name = "conditional_order_type", nullable = false, length = 16)
	private ConditionalOrderType conditionalOrderType;

	@Enumerated(EnumType.STRING)
	@Column(name = "original_status", nullable = false, length = 32)
	private ConditionalOrderStatus originalStatus;

	@Column(nullable = false, length = 32)
	private String symbol;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 8)
	private ConditionalOrderMarket market;

	@Column(nullable = false, precision = 65, scale = 18)
	private BigDecimal quantity;

	@Enumerated(EnumType.STRING)
	@Column(name = "order_type", nullable = false, length = 8)
	private OrderType orderType;

	@Column(name = "expire_date")
	private LocalDate expireDate;

	@Enumerated(EnumType.STRING)
	@Column(name = "first_type", nullable = false, length = 32)
	private ConditionalOrderConditionType firstType;

	@Enumerated(EnumType.STRING)
	@Column(name = "first_status", nullable = false, length = 32)
	private ConditionalOrderConditionStatus firstStatus;

	@Column(name = "first_trigger_price", precision = 65, scale = 18)
	private BigDecimal firstTriggerPrice;

	@Column(name = "first_target_profit_rate", precision = 65, scale = 18)
	private BigDecimal firstTargetProfitRate;

	@Column(name = "first_order_price", precision = 65, scale = 18)
	private BigDecimal firstOrderPrice;

	@Column(name = "first_triggered_order_id", length = 512)
	private String firstTriggeredOrderId;

	@Enumerated(EnumType.STRING)
	@Column(name = "second_type", length = 32)
	private ConditionalOrderConditionType secondType;

	@Enumerated(EnumType.STRING)
	@Column(name = "second_status", length = 32)
	private ConditionalOrderConditionStatus secondStatus;

	@Column(name = "second_trigger_price", precision = 65, scale = 18)
	private BigDecimal secondTriggerPrice;

	@Column(name = "second_target_profit_rate", precision = 65, scale = 18)
	private BigDecimal secondTargetProfitRate;

	@Column(name = "second_order_price", precision = 65, scale = 18)
	private BigDecimal secondOrderPrice;

	@Column(name = "second_triggered_order_id", length = 512)
	private String secondTriggeredOrderId;

	@Column(name = "conditional_order_created_at", nullable = false)
	private OffsetDateTime conditionalOrderCreatedAt;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	private ConditionalOrderCancellationPreviewStatus status;

	@Column(name = "approved_at")
	private OffsetDateTime approvedAt;

	@Column(name = "consumed_at")
	private OffsetDateTime consumedAt;

	@Version
	@Column(nullable = false)
	private long version;

	/** JPA가 데이터베이스 행을 복원할 때 사용하는 빈 생성자입니다. */
	protected ConditionalOrderCancellationPreviewEntity() {
	}

	/** 조건 주문 취소 미리보기의 모든 값을 새 저장 객체에 복사합니다. */
	private ConditionalOrderCancellationPreviewEntity(
			ConditionalOrderCancellationPreviewResponse response) {
		this.previewId = response.previewId();
		this.createdAt = response.createdAt();
		this.expiresAt = response.expiresAt();
		this.accountSeq = response.accountSeq();
		this.conditionalOrderId = response.conditionalOrderId();
		this.conditionalOrderType = response.conditionalOrderType();
		this.originalStatus = response.originalStatus();
		this.symbol = response.symbol();
		this.market = response.market();
		this.quantity = response.quantity();
		this.orderType = response.orderType();
		this.expireDate = response.expireDate();
		copyFirst(response.first());
		copySecond(response.second());
		this.conditionalOrderCreatedAt = response.conditionalOrderCreatedAt();
		this.status = response.status();
		this.approvedAt = response.approvedAt();
	}

	/** 첫 번째 감시 조건의 모든 값을 저장 필드로 복사합니다. */
	private void copyFirst(ConditionSnapshot condition) {
		this.firstType = condition.type();
		this.firstStatus = condition.status();
		this.firstTriggerPrice = condition.triggerPrice();
		this.firstTargetProfitRate = condition.targetProfitRate();
		this.firstOrderPrice = condition.orderPrice();
		this.firstTriggeredOrderId = condition.triggeredOrderId();
	}

	/** 두 번째 감시 조건이 있으면 모든 값을 저장 필드로 복사합니다. */
	private void copySecond(ConditionSnapshot condition) {
		if (condition == null) {
			return;
		}
		this.secondType = condition.type();
		this.secondStatus = condition.status();
		this.secondTriggerPrice = condition.triggerPrice();
		this.secondTargetProfitRate = condition.targetProfitRate();
		this.secondOrderPrice = condition.orderPrice();
		this.secondTriggeredOrderId = condition.triggeredOrderId();
	}

	/** 취소 미리보기 응답을 데이터베이스 저장 객체로 변환합니다. */
	static ConditionalOrderCancellationPreviewEntity from(
			ConditionalOrderCancellationPreviewResponse response) {
		return new ConditionalOrderCancellationPreviewEntity(response);
	}

	/** 데이터베이스에 저장된 값과 현재 상태를 API 응답으로 변환합니다. */
	ConditionalOrderCancellationPreviewResponse toResponse() {
		return new ConditionalOrderCancellationPreviewResponse(
				previewId, createdAt, expiresAt, accountSeq, conditionalOrderId,
				conditionalOrderType, originalStatus, symbol, market, quantity, orderType,
				expireDate, firstSnapshot(), secondSnapshot(), conditionalOrderCreatedAt,
				status, approvedAt);
	}

	/** 저장 필드에서 첫 번째 감시 조건 사본을 복원합니다. */
	private ConditionSnapshot firstSnapshot() {
		return new ConditionSnapshot(
				firstType, firstStatus, firstTriggerPrice, firstTargetProfitRate,
				firstOrderPrice, firstTriggeredOrderId);
	}

	/** 저장 필드에서 선택 두 번째 감시 조건 사본을 복원합니다. */
	private ConditionSnapshot secondSnapshot() {
		if (secondType == null) {
			return null;
		}
		return new ConditionSnapshot(
				secondType, secondStatus, secondTriggerPrice, secondTargetProfitRate,
				secondOrderPrice, secondTriggeredOrderId);
	}
}
