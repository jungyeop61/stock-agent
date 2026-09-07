package com.jusika.backend.ordermodification;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import com.jusika.backend.orderpreview.OrderSide;
import com.jusika.backend.orderpreview.OrderType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** 원주문과 사용자가 요청한 정정 결과를 변경할 수 없는 데이터베이스 행으로 보관합니다. */
@Entity
@Table(name = "order_modification_previews")
class OrderModificationPreviewEntity {

	@Id @Column(name = "preview_id", nullable = false, length = 36)
	private String previewId;
	@Column(name = "created_at", nullable = false)
	private OffsetDateTime createdAt;
	@Column(name = "expires_at", nullable = false)
	private OffsetDateTime expiresAt;
	@Column(name = "account_seq", nullable = false)
	private long accountSeq;
	@Column(name = "original_order_id", nullable = false, length = 512)
	private String originalOrderId;
	@Column(nullable = false, length = 32)
	private String symbol;
	@Enumerated(EnumType.STRING) @Column(nullable = false, length = 8)
	private OrderSide side;
	@Column(name = "original_order_type_code", nullable = false, length = 64)
	private String originalOrderTypeCode;
	@Column(name = "original_time_in_force_code", nullable = false, length = 64)
	private String originalTimeInForceCode;
	@Column(name = "original_price", precision = 65, scale = 18)
	private BigDecimal originalPrice;
	@Column(name = "original_quantity", precision = 65, scale = 18)
	private BigDecimal originalQuantity;
	@Column(name = "original_order_amount", precision = 65, scale = 18)
	private BigDecimal originalOrderAmount;
	@Column(name = "filled_quantity", nullable = false, precision = 65, scale = 18)
	private BigDecimal filledQuantity;
	@Column(nullable = false, length = 3)
	private String currency;
	@Enumerated(EnumType.STRING)
	@Column(name = "requested_order_type", nullable = false, length = 16)
	private OrderType requestedOrderType;
	@Column(name = "requested_quantity", precision = 65, scale = 18)
	private BigDecimal requestedQuantity;
	@Column(name = "requested_price", precision = 65, scale = 18)
	private BigDecimal requestedPrice;
	@Column(name = "reference_price", precision = 65, scale = 18)
	private BigDecimal referencePrice;
	@Column(name = "estimated_order_amount", precision = 65, scale = 18)
	private BigDecimal estimatedOrderAmount;
	@Column(name = "requires_high_value_confirmation", nullable = false)
	private boolean requiresHighValueConfirmation;
	@Enumerated(EnumType.STRING) @Column(nullable = false, length = 32)
	private OrderModificationPreviewStatus status;
	@Column(name = "approved_at")
	private OffsetDateTime approvedAt;
	@Column(name = "consumed_at")
	private OffsetDateTime consumedAt;
	@Version @Column(nullable = false)
	private long version;

	/** JPA가 저장된 행을 객체로 복원할 때 사용하는 빈 생성자입니다. */
	protected OrderModificationPreviewEntity() {
	}

	/** 정정 미리보기 응답의 모든 값을 새 저장 객체에 복사합니다. */
	private OrderModificationPreviewEntity(OrderModificationPreviewResponse response) {
		this.previewId = response.previewId();
		this.createdAt = response.createdAt();
		this.expiresAt = response.expiresAt();
		this.accountSeq = response.accountSeq();
		this.originalOrderId = response.originalOrderId();
		this.symbol = response.symbol();
		this.side = response.side();
		this.originalOrderTypeCode = response.originalOrderTypeCode();
		this.originalTimeInForceCode = response.originalTimeInForceCode();
		this.originalPrice = response.originalPrice();
		this.originalQuantity = response.originalQuantity();
		this.originalOrderAmount = response.originalOrderAmount();
		this.filledQuantity = response.filledQuantity();
		this.currency = response.currency();
		this.requestedOrderType = response.requestedOrderType();
		this.requestedQuantity = response.requestedQuantity();
		this.requestedPrice = response.requestedPrice();
		this.referencePrice = response.referencePrice();
		this.estimatedOrderAmount = response.estimatedOrderAmount();
		this.requiresHighValueConfirmation = response.requiresHighValueConfirmation();
		this.status = response.status();
		this.approvedAt = response.approvedAt();
	}

	/** 정정 미리보기 응답을 데이터베이스 저장 객체로 변환합니다. */
	static OrderModificationPreviewEntity from(OrderModificationPreviewResponse response) {
		return new OrderModificationPreviewEntity(response);
	}

	/** 데이터베이스 값을 정정 미리보기 API 응답으로 변환합니다. */
	OrderModificationPreviewResponse toResponse() {
		return new OrderModificationPreviewResponse(
				previewId, createdAt, expiresAt, accountSeq, originalOrderId, symbol, side,
				originalOrderTypeCode, originalTimeInForceCode, originalPrice, originalQuantity,
				originalOrderAmount, filledQuantity, currency, requestedOrderType,
				requestedQuantity, requestedPrice, referencePrice, estimatedOrderAmount,
				requiresHighValueConfirmation,
				status, approvedAt);
	}
}
