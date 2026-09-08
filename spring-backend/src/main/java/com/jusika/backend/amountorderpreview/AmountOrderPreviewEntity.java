package com.jusika.backend.amountorderpreview;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import com.jusika.backend.orderpreview.OrderPreviewStatus;
import com.jusika.backend.orderpreview.OrderSide;
import com.jusika.backend.orderpreview.OrderType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 미국 주식 달러 금액 매수 미리보기의 변경 불가 계산 결과를 데이터베이스에 보관합니다.
 */
@Entity
@Table(name = "amount_order_previews")
class AmountOrderPreviewEntity {

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

	@Column(name = "order_amount", nullable = false, precision = 65, scale = 30)
	private BigDecimal orderAmount;

	@Column(nullable = false, length = 3)
	private String currency;

	@Column(name = "market_country", nullable = false, length = 2)
	private String marketCountry;

	@Column(name = "reference_price", nullable = false, precision = 65, scale = 18)
	private BigDecimal referencePrice;

	@Column(name = "estimated_quantity", nullable = false, precision = 65, scale = 18)
	private BigDecimal estimatedQuantity;

	@Column(name = "commission_rate", nullable = false, precision = 65, scale = 18)
	private BigDecimal commissionRate;

	@Column(name = "estimated_commission", nullable = false, precision = 65, scale = 18)
	private BigDecimal estimatedCommission;

	@Column(name = "estimated_total_cost", nullable = false, precision = 65, scale = 30)
	private BigDecimal estimatedTotalCost;

	@Column(name = "exchange_rate", nullable = false, precision = 65, scale = 18)
	private BigDecimal exchangeRate;

	@Column(name = "exchange_rate_valid_from", nullable = false)
	private OffsetDateTime exchangeRateValidFrom;

	@Column(name = "exchange_rate_valid_until", nullable = false)
	private OffsetDateTime exchangeRateValidUntil;

	@Column(name = "estimated_order_amount_krw", nullable = false, precision = 65, scale = 18)
	private BigDecimal estimatedOrderAmountKrw;

	@Column(name = "requires_high_value_confirmation", nullable = false)
	private boolean requiresHighValueConfirmation;

	@Column(name = "order_ready", nullable = false)
	private boolean orderReady;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	private OrderPreviewStatus status;

	/**
	 * JPA가 데이터베이스 행을 객체로 복원할 때 사용할 빈 생성자입니다.
	 */
	protected AmountOrderPreviewEntity() {
	}

	/**
	 * API 미리보기 응답의 모든 계산 결과를 저장용 객체로 복사합니다.
	 *
	 * @param preview 저장할 금액 주문 미리보기
	 */
	private AmountOrderPreviewEntity(AmountOrderPreviewResponse preview) {
		this.previewId = preview.previewId();
		this.createdAt = preview.createdAt();
		this.expiresAt = preview.expiresAt();
		this.accountSeq = preview.accountSeq();
		this.symbol = preview.symbol();
		this.side = preview.side();
		this.orderType = preview.orderType();
		this.orderAmount = preview.orderAmount();
		this.currency = preview.currency();
		this.marketCountry = preview.marketCountry();
		this.referencePrice = preview.referencePrice();
		this.estimatedQuantity = preview.estimatedQuantity();
		this.commissionRate = preview.commissionRate();
		this.estimatedCommission = preview.estimatedCommission();
		this.estimatedTotalCost = preview.estimatedTotalCost();
		this.exchangeRate = preview.exchangeRate();
		this.exchangeRateValidFrom = preview.exchangeRateValidFrom();
		this.exchangeRateValidUntil = preview.exchangeRateValidUntil();
		this.estimatedOrderAmountKrw = preview.estimatedOrderAmountKrw();
		this.requiresHighValueConfirmation = preview.requiresHighValueConfirmation();
		this.orderReady = preview.orderReady();
		this.status = preview.status();
	}

	/**
	 * API 응답을 데이터베이스 저장 객체로 변환합니다.
	 *
	 * @param preview 저장할 금액 주문 미리보기
	 * @return 모든 계산 결과가 복사된 저장 객체
	 */
	static AmountOrderPreviewEntity from(AmountOrderPreviewResponse preview) {
		return new AmountOrderPreviewEntity(preview);
	}

	/**
	 * 데이터베이스에서 읽은 값을 API 응답으로 변환합니다.
	 *
	 * @return 저장된 값을 그대로 담은 금액 주문 미리보기 응답
	 */
	AmountOrderPreviewResponse toResponse() {
		return new AmountOrderPreviewResponse(
				previewId,
				createdAt,
				expiresAt,
				accountSeq,
				symbol,
				side,
				orderType,
				orderAmount,
				currency,
				marketCountry,
				referencePrice,
				estimatedQuantity,
				commissionRate,
				estimatedCommission,
				estimatedTotalCost,
				exchangeRate,
				exchangeRateValidFrom,
				exchangeRateValidUntil,
				estimatedOrderAmountKrw,
				requiresHighValueConfirmation,
				orderReady,
				status);
	}
}
