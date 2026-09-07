package com.jusika.backend.conditionalordercreation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

import com.jusika.backend.conditionalorder.ConditionalOrderType;
import com.jusika.backend.conditionalordercreation.OtoConditionalOrderPreviewResponse.Condition;
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

/** OTO 조건 주문 미리보기의 두 조건·예상 금액·승인 상태를 데이터베이스에 보관합니다. */
@Entity
@Table(name = "oto_conditional_order_previews")
class OtoConditionalOrderPreviewEntity {

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
	@Column(nullable = false, precision = 65, scale = 18)
	private BigDecimal quantity;
	@Enumerated(EnumType.STRING)
	@Column(name = "order_type", nullable = false, length = 8)
	private OrderType orderType;
	@Column(name = "expire_date", nullable = false)
	private LocalDate expireDate;
	@Column(name = "reference_price", nullable = false, precision = 65, scale = 18)
	private BigDecimal referencePrice;
	@Column(nullable = false, length = 3)
	private String currency;
	@Column(name = "market_country", nullable = false, length = 2)
	private String marketCountry;
	@Column(name = "commission_rate", nullable = false, precision = 65, scale = 18)
	private BigDecimal commissionRate;
	@Enumerated(EnumType.STRING)
	@Column(name = "first_side", nullable = false, length = 8)
	private OrderSide firstSide;
	@Column(name = "first_trigger_price", nullable = false, precision = 65, scale = 18)
	private BigDecimal firstTriggerPrice;
	@Column(name = "first_order_price", nullable = false, precision = 65, scale = 18)
	private BigDecimal firstOrderPrice;
	@Column(name = "first_estimated_order_amount", nullable = false, precision = 65, scale = 18)
	private BigDecimal firstEstimatedOrderAmount;
	@Column(name = "first_estimated_commission", nullable = false, precision = 65, scale = 18)
	private BigDecimal firstEstimatedCommission;
	@Column(name = "first_estimated_after_commission", nullable = false, precision = 65, scale = 18)
	private BigDecimal firstEstimatedAfterCommission;
	@Enumerated(EnumType.STRING)
	@Column(name = "second_side", nullable = false, length = 8)
	private OrderSide secondSide;
	@Column(name = "second_trigger_price", nullable = false, precision = 65, scale = 18)
	private BigDecimal secondTriggerPrice;
	@Column(name = "second_order_price", nullable = false, precision = 65, scale = 18)
	private BigDecimal secondOrderPrice;
	@Column(name = "second_estimated_order_amount", nullable = false, precision = 65, scale = 18)
	private BigDecimal secondEstimatedOrderAmount;
	@Column(name = "second_estimated_commission", nullable = false, precision = 65, scale = 18)
	private BigDecimal secondEstimatedCommission;
	@Column(name = "second_estimated_after_commission", nullable = false, precision = 65, scale = 18)
	private BigDecimal secondEstimatedAfterCommission;
	@Column(name = "buying_power_checked", nullable = false)
	private boolean buyingPowerChecked;
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
	protected OtoConditionalOrderPreviewEntity() {
	}

	/** API OTO 미리보기 응답의 모든 변경 불가 값을 저장 객체에 복사합니다. */
	private OtoConditionalOrderPreviewEntity(OtoConditionalOrderPreviewResponse preview) {
		this.previewId = preview.previewId();
		this.createdAt = preview.createdAt();
		this.expiresAt = preview.expiresAt();
		this.accountSeq = preview.accountSeq();
		this.symbol = preview.symbol();
		this.conditionalOrderType = preview.conditionalOrderType();
		this.quantity = preview.quantity();
		this.orderType = preview.orderType();
		this.expireDate = preview.expireDate();
		this.referencePrice = preview.referencePrice();
		this.currency = preview.currency();
		this.marketCountry = preview.marketCountry();
		this.commissionRate = preview.commissionRate();
		this.firstSide = preview.first().side();
		this.firstTriggerPrice = preview.first().triggerPrice();
		this.firstOrderPrice = preview.first().orderPrice();
		this.firstEstimatedOrderAmount = preview.first().estimatedOrderAmount();
		this.firstEstimatedCommission = preview.first().estimatedCommission();
		this.firstEstimatedAfterCommission = preview.first().estimatedAmountAfterCommission();
		this.secondSide = preview.second().side();
		this.secondTriggerPrice = preview.second().triggerPrice();
		this.secondOrderPrice = preview.second().orderPrice();
		this.secondEstimatedOrderAmount = preview.second().estimatedOrderAmount();
		this.secondEstimatedCommission = preview.second().estimatedCommission();
		this.secondEstimatedAfterCommission = preview.second().estimatedAmountAfterCommission();
		this.buyingPowerChecked = preview.buyingPowerChecked();
		this.sellTaxExcluded = preview.sellTaxExcluded();
		this.requiresHighValueConfirmation = preview.requiresHighValueConfirmation();
		this.status = preview.status();
		this.approvedAt = preview.approvedAt();
	}

	/** API 응답을 데이터베이스 저장 객체로 변환합니다. */
	static OtoConditionalOrderPreviewEntity from(OtoConditionalOrderPreviewResponse preview) {
		return new OtoConditionalOrderPreviewEntity(preview);
	}

	/** 데이터베이스 값을 OTO 조건 주문 미리보기 응답으로 변환합니다. */
	OtoConditionalOrderPreviewResponse toResponse() {
		return new OtoConditionalOrderPreviewResponse(
				previewId, createdAt, expiresAt, accountSeq, symbol, conditionalOrderType,
				quantity, orderType, expireDate, referencePrice, currency, marketCountry,
				commissionRate,
				new Condition(firstSide, firstTriggerPrice, firstOrderPrice,
						firstEstimatedOrderAmount, firstEstimatedCommission,
						firstEstimatedAfterCommission),
				new Condition(secondSide, secondTriggerPrice, secondOrderPrice,
						secondEstimatedOrderAmount, secondEstimatedCommission,
						secondEstimatedAfterCommission),
				buyingPowerChecked, sellTaxExcluded, requiresHighValueConfirmation,
				status, approvedAt);
	}
}
