package com.jusika.backend.ordermodification;

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
import com.jusika.backend.orderpreview.OrderSide;
import com.jusika.backend.orderpreview.OrderType;

/** 플라이웨이 V5 테이블에서 원주문 단위 정정 중복 방지와 상태 저장을 검사합니다. */
@SpringBootTest
class OrderModificationPersistenceTests {
	@Autowired
	private OrderModificationPreviewStore previewStore;
	@Autowired
	private OrderModificationExecutionStore executionStore;

	/** 같은 원주문의 여러 미리보기 중 첫 정정 실행만 만들고 새 주문번호를 저장하는지 검사합니다. */
	@Test
	@DisplayName("원주문 하나의 정정 실행권을 데이터베이스에서 한 번만 만든다")
	void 원주문_하나의_정정_실행권을_데이터베이스에서_한_번만_만든다() {
		OffsetDateTime createdAt = OffsetDateTime.of(
				2026, 9, 7, 14, 0, 0, 0, ZoneOffset.ofHours(9));
		String orderId = "test-modify-" + UUID.randomUUID();
		OrderModificationPreviewResponse firstPreview = 미리보기를_저장한다(orderId, createdAt);
		OrderModificationPreviewResponse secondPreview = 미리보기를_저장한다(
				orderId, createdAt.plusSeconds(1));
		OrderModificationExecutionResponse first = 실행_준비_기록을_만든다(
				firstPreview.previewId(), orderId, createdAt.plusSeconds(2));
		OrderModificationExecutionResponse duplicate = 실행_준비_기록을_만든다(
				secondPreview.previewId(), orderId, createdAt.plusSeconds(3));

		assertThat(executionStore.claim(first)).isTrue();
		assertThat(executionStore.claim(duplicate)).isFalse();
		assertThat(executionStore.markSubmitting(first.executionId(), createdAt.plusSeconds(4))).isTrue();
		assertThat(executionStore.markAccepted(
				first.executionId(), "new-modified-order", createdAt.plusSeconds(5))).isTrue();
		assertThat(executionStore.markAccepted(
				first.executionId(), "other-order", createdAt.plusSeconds(6))).isFalse();

		OrderModificationExecutionResponse stored = executionStore
				.findByOriginalOrderId(orderId).orElseThrow();
		assertThat(stored.status()).isEqualTo(OrderExecutionStatus.ACCEPTED);
		assertThat(stored.operationOrderId()).isEqualTo("new-modified-order");
		assertThat(stored.submittedAt()).isEqualTo(createdAt.plusSeconds(4));
		assertThat(stored.completedAt()).isEqualTo(createdAt.plusSeconds(5));
	}

	/** 데이터베이스 테스트에 사용할 승인된 국내 지정가 정정 미리보기를 저장합니다. */
	private OrderModificationPreviewResponse 미리보기를_저장한다(
			String orderId, OffsetDateTime createdAt) {
		return previewStore.save(new OrderModificationPreviewResponse(
				UUID.randomUUID().toString(), createdAt, createdAt.plusMinutes(2), 1L,
				orderId, "005930", OrderSide.BUY, "LIMIT", "DAY",
				new BigDecimal("70000"), new BigDecimal("10"), null, BigDecimal.ZERO,
				"KRW", OrderType.LIMIT, new BigDecimal("15"), new BigDecimal("71000"),
				null, new BigDecimal("1065000"), false,
				OrderModificationPreviewStatus.APPROVED, createdAt));
	}

	/** 저장된 정정 미리보기와 연결할 실행 준비 기록을 만듭니다. */
	private OrderModificationExecutionResponse 실행_준비_기록을_만든다(
			String previewId, String orderId, OffsetDateTime createdAt) {
		return new OrderModificationExecutionResponse(
				UUID.randomUUID().toString(), previewId, orderId, null, "MOCK",
				OrderExecutionStatus.PREPARED, null, createdAt, createdAt, null, null);
	}
}
