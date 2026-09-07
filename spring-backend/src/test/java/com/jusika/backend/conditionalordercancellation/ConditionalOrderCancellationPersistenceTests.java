package com.jusika.backend.conditionalordercancellation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.jusika.backend.conditionalorder.ConditionalOrderConditionStatus;
import com.jusika.backend.conditionalorder.ConditionalOrderConditionType;
import com.jusika.backend.conditionalorder.ConditionalOrderMarket;
import com.jusika.backend.conditionalorder.ConditionalOrderStatus;
import com.jusika.backend.conditionalorder.ConditionalOrderType;
import com.jusika.backend.conditionalordercancellation.ConditionalOrderCancellationPreviewResponse.ConditionSnapshot;
import com.jusika.backend.orderexecution.OrderExecutionStatus;
import com.jusika.backend.orderpreview.OrderType;

/** 플라이웨이가 만든 H2 테이블에서 조건 주문 취소 승인과 중복 방지를 검사합니다. */
@SpringBootTest
class ConditionalOrderCancellationPersistenceTests {

	@Autowired
	private ConditionalOrderCancellationPreviewStore previewStore;

	@Autowired
	private ConditionalOrderCancellationExecutionStore executionStore;

	/** 같은 계좌의 같은 조건 주문에는 첫 취소 실행만 저장하고 성공 상태를 기록하는지 검사합니다. */
	@Test
	@DisplayName("같은 조건 주문의 취소 실행권을 데이터베이스에서 한 번만 만든다")
	void 같은_조건_주문의_취소_실행권을_데이터베이스에서_한_번만_만든다() {
		OffsetDateTime createdAt = OffsetDateTime.of(
				2026, 9, 7, 21, 0, 0, 0, ZoneOffset.ofHours(9));
		String conditionalOrderId = "test-conditional-" + UUID.randomUUID();
		ConditionalOrderCancellationPreviewResponse firstPreview =
				미리보기를_저장한다(conditionalOrderId, createdAt);
		ConditionalOrderCancellationPreviewResponse secondPreview =
				미리보기를_저장한다(conditionalOrderId, createdAt.plusSeconds(1));
		assertThat(previewStore.approvePending(
				firstPreview.previewId(), createdAt.plusSeconds(2))).isTrue();
		assertThat(previewStore.consumeApproved(
				firstPreview.previewId(), createdAt.plusSeconds(3))).isTrue();

		ConditionalOrderCancellationExecutionResponse first = 실행_준비_기록을_만든다(
				firstPreview.previewId(), conditionalOrderId, createdAt.plusSeconds(3));
		ConditionalOrderCancellationExecutionResponse duplicate = 실행_준비_기록을_만든다(
				secondPreview.previewId(), conditionalOrderId, createdAt.plusSeconds(4));

		assertThat(executionStore.claim(first)).isTrue();
		assertThat(executionStore.claim(duplicate)).isFalse();
		assertThat(executionStore.markSubmitting(
				first.executionId(), createdAt.plusSeconds(5))).isTrue();
		assertThat(executionStore.markAccepted(
				first.executionId(), createdAt.plusSeconds(6))).isTrue();
		assertThat(executionStore.markAccepted(
				first.executionId(), createdAt.plusSeconds(7))).isFalse();

		ConditionalOrderCancellationExecutionResponse stored = executionStore
				.findByTarget(1L, conditionalOrderId).orElseThrow();
		assertThat(stored.status()).isEqualTo(OrderExecutionStatus.ACCEPTED);
		assertThat(stored.submittedAt()).isEqualTo(createdAt.plusSeconds(5));
		assertThat(stored.completedAt()).isEqualTo(createdAt.plusSeconds(6));
		assertThat(previewStore.findById(firstPreview.previewId()).orElseThrow().status())
				.isEqualTo(ConditionalOrderCancellationPreviewStatus.CONSUMED);
	}

	/** 데이터베이스 테스트에 사용할 승인 대기 조건 주문 취소 미리보기를 저장합니다. */
	private ConditionalOrderCancellationPreviewResponse 미리보기를_저장한다(
			String conditionalOrderId, OffsetDateTime createdAt) {
		ConditionSnapshot first = new ConditionSnapshot(
				ConditionalOrderConditionType.STOP,
				ConditionalOrderConditionStatus.WATCHING,
				new BigDecimal("210"), null, new BigDecimal("209"), null);
		ConditionSnapshot second = new ConditionSnapshot(
				ConditionalOrderConditionType.STOP,
				ConditionalOrderConditionStatus.WATCHING,
				new BigDecimal("190"), null, new BigDecimal("189"), null);
		return previewStore.save(new ConditionalOrderCancellationPreviewResponse(
				UUID.randomUUID().toString(), createdAt, createdAt.plusMinutes(2), 1L,
				conditionalOrderId, ConditionalOrderType.OCO, ConditionalOrderStatus.WATCHING,
				"AAPL", ConditionalOrderMarket.US, new BigDecimal("10"), OrderType.LIMIT,
				LocalDate.of(2026, 9, 10), first, second, createdAt.minusMinutes(5),
				ConditionalOrderCancellationPreviewStatus.PENDING_APPROVAL, null));
	}

	/** 조건 주문 취소 미리보기와 연결된 실행 준비 기록을 만듭니다. */
	private ConditionalOrderCancellationExecutionResponse 실행_준비_기록을_만든다(
			String previewId, String conditionalOrderId, OffsetDateTime createdAt) {
		return new ConditionalOrderCancellationExecutionResponse(
				UUID.randomUUID().toString(), previewId, 1L, conditionalOrderId, "MOCK",
				OrderExecutionStatus.PREPARED, null, createdAt, createdAt, null, null);
	}
}
