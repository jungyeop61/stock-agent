package com.jusika.backend.ordermodification;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import com.jusika.backend.orderpreview.OrderSide;
import com.jusika.backend.orderpreview.OrderType;

/** 사용자가 승인하기 전에 읽어 줄 원주문과 정정 결과의 변경 불가 사본입니다. */
public record OrderModificationPreviewResponse(
		String previewId,
		OffsetDateTime createdAt,
		OffsetDateTime expiresAt,
		long accountSeq,
		String originalOrderId,
		String symbol,
		OrderSide side,
		String originalOrderTypeCode,
		String originalTimeInForceCode,
		BigDecimal originalPrice,
		BigDecimal originalQuantity,
		BigDecimal originalOrderAmount,
		BigDecimal filledQuantity,
		String currency,
		OrderType requestedOrderType,
		BigDecimal requestedQuantity,
		BigDecimal requestedPrice,
		BigDecimal referencePrice,
		BigDecimal estimatedOrderAmount,
		boolean requiresHighValueConfirmation,
		OrderModificationPreviewStatus status,
		OffsetDateTime approvedAt) {

	/** 로그에 모든 주문 식별값과 금융값이 노출되지 않도록 가립니다. */
	@Override
	public String toString() {
		return "OrderModificationPreviewResponse[previewId=***, accountSeq=***"
				+ ", originalOrderId=***, symbol=" + symbol + ", side=" + side
				+ ", originalOrderTypeCode=" + originalOrderTypeCode
				+ ", originalTimeInForceCode=" + originalTimeInForceCode
				+ ", originalPrice=***, originalQuantity=***, originalOrderAmount=***"
				+ ", filledQuantity=***, currency=" + currency
				+ ", requestedOrderType=" + requestedOrderType
				+ ", requestedQuantity=***, requestedPrice=***, referencePrice=***"
				+ ", estimatedOrderAmount=***"
				+ ", requiresHighValueConfirmation=" + requiresHighValueConfirmation
				+ ", status=" + status + ", createdAt=" + createdAt
				+ ", expiresAt=" + expiresAt + ", approvedAt=" + approvedAt + "]";
	}
}
