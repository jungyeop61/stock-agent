package com.jusika.backend.conditionalordercreation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

import com.jusika.backend.conditionalorder.ConditionalOrderType;
import com.jusika.backend.orderpreview.OrderPreviewStatus;
import com.jusika.backend.orderpreview.OrderSide;
import com.jusika.backend.orderpreview.OrderType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * 단일 조건 주문 미리보기의 계산 결과와 승인 상태를 데이터베이스에 보관합니다.
 */
@Entity
@Table(name = "single_conditional_order_previews")
class SingleConditionalOrderPreviewEntity {

	@Id
	@Column(name = "preview_id", nullable = false, length = 36)
	private String previewId;

	@Column(name = "created_at", nullable = false)
	private OffsetDateTime createdAt;

	@Column(name = "expires_at", nullable = false)
	private OffsetDateTime expiresAt;

	@Column(name = "account_seq", nullable = false)
	private long accountSeq;

	@Column(nullable = false, length = 32)
	private String symbol;

	@Enumerated(EnumType.STRING)
	@Column(name = "conditional_order_type", nullable = false, length = 16)
	private ConditionalOrderType conditionalOrderType;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 8)
	private OrderSide side;

	@Enumerated(EnumType.STRING)
	@Column(name = "order_type", nullable = false, length = 8)
	private OrderType orderType;

	@Column(nullable = false, precision = 65, scale = 18)
	private BigDecimal quantity;

	@Column(name = "trigger_price", nullable = false, precision = 65, scale = 18)
	private BigDecimal triggerPrice;

	@Column(name = "order_price", precision = 65, scale = 18)
	private BigDecimal orderPrice;

	@Column(name = "expire_date", nullable = false)
	private LocalDate expireDate;

	@Column(name = "reference_price", nullable = false, precision = 65, scale = 18)
	private BigDecimal referencePrice;

	@Column(name = "calculation_price", nullable = false, precision = 65, scale = 18)
	private BigDecimal calculationPrice;

	@Column(nullable = false, length = 3)
	private String currency;

	@Column(name = "market_country", nullable = false, length = 2)
	private String marketCountry;

	@Column(name = "commission_rate", nullable = false, precision = 65, scale = 18)
	private BigDecimal commissionRate;

	@Column(name = "estimated_order_amount", nullable = false, precision = 65, scale = 18)
	private BigDecimal estimatedOrderAmount;

	@Column(name = "estimated_commission", nullable = false, precision = 65, scale = 18)
	private BigDecimal estimatedCommission;

	@Column(name = "estimated_amount_after_commission", nullable = false, precision = 65, scale = 18)
	private BigDecimal estimatedAmountAfterCommission;

	@Column(name = "sell_tax_excluded", nullable = false)
	private boolean sellTaxExcluded;

	@Column(name = "requires_high_value_confirmation", nullable = false)
	private boolean requiresHighValueConfirmation;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	private OrderPreviewStatus status;

	@Column(name = "approved_at")
	private OffsetDateTime approvedAt;

	@Column(name = "consumed_at")
	private OffsetDateTime consumedAt;

	@Version
	@Column(nullable = false)
	private long version;

	/** JPA가 저장 행을 객체로 복원할 때 사용할 빈 생성자입니다. */
	protected SingleConditionalOrderPreviewEntity() {
	}

	/** API 미리보기 응답의 모든 변경 불가 값을 저장 객체에 복사합니다. */
	private SingleConditionalOrderPreviewEntity(SingleConditionalOrderPreviewResponse preview) {
		this.previewId = preview.previewId();
		this.createdAt = preview.createdAt();
		this.expiresAt = preview.expiresAt();
		this.accountSeq = preview.accountSeq();
		this.symbol = preview.symbol();
		this.conditionalOrderType = preview.conditionalOrderType();
		this.side = preview.side();
		this.orderType = preview.orderType();
		this.quantity = preview.quantity();
		this.triggerPrice = preview.triggerPrice();
		this.orderPrice = preview.orderPrice();
		this.expireDate = preview.expireDate();
		this.referencePrice = preview.referencePrice();
		this.calculationPrice = preview.calculationPrice();
		this.currency = preview.currency();
		this.marketCountry = preview.marketCountry();
		this.commissionRate = preview.commissionRate();
		this.estimatedOrderAmount = preview.estimatedOrderAmount();
		this.estimatedCommission = preview.estimatedCommission();
		this.estimatedAmountAfterCommission = preview.estimatedAmountAfterCommission();
		this.sellTaxExcluded = preview.sellTaxExcluded();
		this.requiresHighValueConfirmation = preview.requiresHighValueConfirmation();
		this.status = preview.status();
		this.approvedAt = preview.approvedAt();
	}

	/** API 응답을 데이터베이스 저장 객체로 변환합니다. */
	static SingleConditionalOrderPreviewEntity from(
			SingleConditionalOrderPreviewResponse preview) {
		return new SingleConditionalOrderPreviewEntity(preview);
	}

	/** 데이터베이스 값을 단일 조건 주문 미리보기 응답으로 변환합니다. */
	SingleConditionalOrderPreviewResponse toResponse() {
		return new SingleConditionalOrderPreviewResponse(
				previewId, createdAt, expiresAt, accountSeq, symbol, conditionalOrderType,
				side, orderType, quantity, triggerPrice, orderPrice, expireDate,
				referencePrice, calculationPrice, currency, marketCountry, commissionRate,
				estimatedOrderAmount, estimatedCommission, estimatedAmountAfterCommission,
				sellTaxExcluded, requiresHighValueConfirmation, status, approvedAt);
	}
}
