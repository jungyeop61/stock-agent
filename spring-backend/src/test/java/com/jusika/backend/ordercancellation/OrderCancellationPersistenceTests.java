package com.jusika.backend.ordercancellation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.jusika.backend.orderexecution.OrderExecutionStatus;
import com.jusika.backend.orderhistory.OrderStatus;
import com.jusika.backend.orderpreview.OrderSide;

/** 플라이웨이가 만든 H2 테이블에서 취소 승인과 원주문 단위 중복 방지를 검사합니다. */
@SpringBootTest
class OrderCancellationPersistenceTests {

	@Autowired
	private OrderCancellationPreviewStore previewStore;

	@Autowired
	private OrderCancellationExecutionStore executionStore;

	/** 같은 원주문의 두 미리보기 중 첫 실행만 확보하고 접수 상태를 저장하는지 검사합니다. */
	@Test
	@DisplayName("같은 원주문의 취소 실행권을 데이터베이스에서 한 번만 만든다")
	void 같은_원주문의_취소_실행권을_데이터베이스에서_한_번만_만든다() {
		OffsetDateTime createdAt = OffsetDateTime.of(
				2026, 9, 6, 21, 0, 0, 0, ZoneOffset.ofHours(9));
		String orderId = "test-order-" + UUID.randomUUID();
		OrderCancellationPreviewResponse firstPreview = 미리보기를_저장한다(orderId, createdAt);
		OrderCancellationPreviewResponse secondPreview = 미리보기를_저장한다(orderId, createdAt.plusSeconds(1));
		assertThat(previewStore.approvePending(firstPreview.previewId(), createdAt.plusSeconds(2))).isTrue();
		assertThat(previewStore.consumeApproved(firstPreview.previewId(), createdAt.plusSeconds(3))).isTrue();

		OrderCancellationExecutionResponse first = 실행_준비_기록을_만든다(
				firstPreview.previewId(), orderId, createdAt.plusSeconds(3));
		OrderCancellationExecutionResponse duplicate = 실행_준비_기록을_만든다(
				secondPreview.previewId(), orderId, createdAt.plusSeconds(4));

		assertThat(executionStore.claim(first)).isTrue();
		assertThat(executionStore.claim(duplicate)).isFalse();
		assertThat(executionStore.markSubmitting(first.executionId(), createdAt.plusSeconds(5))).isTrue();
		assertThat(executionStore.markAccepted(first.executionId(), createdAt.plusSeconds(6))).isTrue();
		assertThat(executionStore.markAccepted(first.executionId(), createdAt.plusSeconds(7))).isFalse();

		OrderCancellationExecutionResponse stored = executionStore
				.findByOrderId(orderId).orElseThrow();
		assertThat(stored.status()).isEqualTo(OrderExecutionStatus.ACCEPTED);
		assertThat(stored.submittedAt()).isEqualTo(createdAt.plusSeconds(5));
		assertThat(stored.completedAt()).isEqualTo(createdAt.plusSeconds(6));
		assertThat(previewStore.findById(firstPreview.previewId()).orElseThrow().status())
				.isEqualTo(OrderCancellationPreviewStatus.CONSUMED);
	}

	/** 데이터베이스 테스트에 사용할 승인 대기 취소 미리보기를 저장합니다. */
	private OrderCancellationPreviewResponse 미리보기를_저장한다(
			String orderId, OffsetDateTime createdAt) {
		return previewStore.save(new OrderCancellationPreviewResponse(
				UUID.randomUUID().toString(), createdAt, createdAt.plusMinutes(2), 1L,
				orderId, "005930", OrderSide.BUY, "LIMIT", OrderStatus.PENDING,
				new BigDecimal("70000"), new BigDecimal("10"), BigDecimal.ZERO,
				new BigDecimal("10"), null, "KRW",
				OrderCancellationPreviewStatus.PENDING_APPROVAL, null));
	}

	/** 취소 미리보기와 연결된 실행 준비 기록을 만듭니다. */
	private OrderCancellationExecutionResponse 실행_준비_기록을_만든다(
			String previewId, String orderId, OffsetDateTime createdAt) {
		return new OrderCancellationExecutionResponse(
				UUID.randomUUID().toString(), previewId, orderId, "MOCK",
				OrderExecutionStatus.PREPARED, null, createdAt, createdAt, null, null);
	}
}
