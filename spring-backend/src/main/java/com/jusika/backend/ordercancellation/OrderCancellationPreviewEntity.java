package com.jusika.backend.ordercancellation;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import com.jusika.backend.orderhistory.OrderStatus;
import com.jusika.backend.orderpreview.OrderSide;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * 사용자가 확인할 취소 대상 주문의 변경 불가 정보를 데이터베이스에 보관합니다.
 */
@Entity
@Table(name = "order_cancellation_previews")
class OrderCancellationPreviewEntity {

	@Id
	@Column(name = "preview_id", nullable = false, length = 36)
	private String previewId;

	@Column(name = "created_at", nullable = false)
	private OffsetDateTime createdAt;

	@Column(name = "expires_at", nullable = false)
	private OffsetDateTime expiresAt;

	@Column(name = "account_seq", nullable = false)
	private long accountSeq;

	@Column(name = "order_id", nullable = false, length = 512)
	private String orderId;

	@Column(nullable = false, length = 32)
	private String symbol;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 8)
	private OrderSide side;

	@Column(name = "order_type_code", nullable = false, length = 64)
	private String orderTypeCode;

	@Enumerated(EnumType.STRING)
	@Column(name = "original_status", nullable = false, length = 32)
	private OrderStatus originalStatus;

	@Column(precision = 65, scale = 18)
	private BigDecimal price;

	@Column(precision = 65, scale = 18)
	private BigDecimal quantity;

	@Column(name = "filled_quantity", nullable = false, precision = 65, scale = 18)
	private BigDecimal filledQuantity;

	@Column(name = "remaining_quantity", precision = 65, scale = 18)
	private BigDecimal remainingQuantity;

	@Column(name = "order_amount", precision = 65, scale = 18)
	private BigDecimal orderAmount;

	@Column(nullable = false, length = 3)
	private String currency;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	private OrderCancellationPreviewStatus status;

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
	protected OrderCancellationPreviewEntity() {
	}

	/**
	 * 취소 미리보기의 모든 변경 불가 값을 새 저장 객체에 복사합니다.
	 *
	 * @param response 저장할 취소 미리보기
	 */
	private OrderCancellationPreviewEntity(OrderCancellationPreviewResponse response) {
		this.previewId = response.previewId();
		this.createdAt = response.createdAt();
		this.expiresAt = response.expiresAt();
		this.accountSeq = response.accountSeq();
		this.orderId = response.orderId();
		this.symbol = response.symbol();
		this.side = response.side();
		this.orderTypeCode = response.orderTypeCode();
		this.originalStatus = response.originalStatus();
		this.price = response.price();
		this.quantity = response.quantity();
		this.filledQuantity = response.filledQuantity();
		this.remainingQuantity = response.remainingQuantity();
		this.orderAmount = response.orderAmount();
		this.currency = response.currency();
		this.status = response.status();
		this.approvedAt = response.approvedAt();
	}

	/**
	 * 취소 미리보기 응답을 데이터베이스 저장 객체로 변환합니다.
	 *
	 * @param response 저장할 취소 미리보기
	 * @return 모든 취소 대상 값이 복사된 저장 객체
	 */
	static OrderCancellationPreviewEntity from(OrderCancellationPreviewResponse response) {
		return new OrderCancellationPreviewEntity(response);
	}

	/**
	 * 데이터베이스에 저장된 취소 대상과 현재 상태를 API 응답으로 변환합니다.
	 *
	 * @return 저장된 값을 그대로 담은 취소 미리보기 응답
	 */
	OrderCancellationPreviewResponse toResponse() {
		return new OrderCancellationPreviewResponse(
				previewId, createdAt, expiresAt, accountSeq, orderId, symbol, side,
				orderTypeCode, originalStatus, price, quantity, filledQuantity,
				remainingQuantity, orderAmount, currency, status, approvedAt);
	}
}
