package com.jusika.backend.orderpreview;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * 주문 미리보기의 계산 결과와 승인 상태를 데이터베이스에 보관합니다.
 */
@Entity
@Table(name = "order_previews")
class OrderPreviewEntity {

	@Id
	@Column(name = "preview_id", nullable = false, length = 36)
	private String previewId;

	@Column(name = "created_at", nullable = false)
	private OffsetDateTime createdAt;

	@Column(name = "expires_at", nullable = false)
	private OffsetDateTime expiresAt;

	@Column(name = "account_seq", nullable = false)
	private long accountSeq;

	@Column(nullable = false, length = 30)
	private String symbol;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 8)
	private OrderSide side;

	@Enumerated(EnumType.STRING)
	@Column(name = "order_type", nullable = false, length = 8)
	private OrderType orderType;

	@Column(nullable = false, precision = 65, scale = 18)
	private BigDecimal quantity;

	@Column(name = "requested_price", precision = 65, scale = 18)
	private BigDecimal requestedPrice;

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

	@Column(name = "order_ready", nullable = false)
	private boolean orderReady;

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

	/**
	 * JPA가 데이터베이스 행을 객체로 복원할 때 사용할 빈 생성자입니다.
	 */
	protected OrderPreviewEntity() {
	}

	/**
	 * API 미리보기 응답의 모든 변경 불가 주문 내용을 저장용 객체로 복사합니다.
	 *
	 * @param preview 저장할 주문 미리보기
	 */
	private OrderPreviewEntity(OrderPreviewResponse preview) {
		this.previewId = preview.previewId();
		this.createdAt = preview.createdAt();
		this.expiresAt = preview.expiresAt();
		this.accountSeq = preview.accountSeq();
		this.symbol = preview.symbol();
		this.side = preview.side();
		this.orderType = preview.orderType();
		this.quantity = preview.quantity();
		this.requestedPrice = preview.requestedPrice();
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
		this.orderReady = preview.orderReady();
		this.status = preview.status();
		this.approvedAt = preview.approvedAt();
	}

	/**
	 * API 응답을 데이터베이스 저장 객체로 변환합니다.
	 *
	 * @param preview 저장할 주문 미리보기
	 * @return 모든 주문 내용이 복사된 저장 객체
	 */
	static OrderPreviewEntity from(OrderPreviewResponse preview) {
		return new OrderPreviewEntity(preview);
	}

	/**
	 * 데이터베이스에서 읽은 주문 내용과 현재 상태를 API 응답으로 변환합니다.
	 *
	 * @return 저장된 값을 그대로 담은 주문 미리보기 응답
	 */
	OrderPreviewResponse toResponse() {
		return new OrderPreviewResponse(
				previewId,
				createdAt,
				expiresAt,
				accountSeq,
				symbol,
				side,
				orderType,
				quantity,
				requestedPrice,
				referencePrice,
				calculationPrice,
				currency,
				marketCountry,
				commissionRate,
				estimatedOrderAmount,
				estimatedCommission,
				estimatedAmountAfterCommission,
				sellTaxExcluded,
				requiresHighValueConfirmation,
				orderReady,
				status,
				approvedAt);
	}
}
