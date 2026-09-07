package com.jusika.backend.conditionalordercreation;

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

import com.jusika.backend.conditionalorder.ConditionalOrderType;
import com.jusika.backend.orderexecution.OrderExecutionStatus;
import com.jusika.backend.orderpreview.OrderPreviewStatus;
import com.jusika.backend.orderpreview.OrderSide;
import com.jusika.backend.orderpreview.OrderType;

/**
 * 플라이웨이 V6 테이블에서 단일 조건 주문의 승인·중복 방지·실행 상태 저장을 검사합니다.
 */
@SpringBootTest
class SingleConditionalOrderPersistenceTests {

	@Autowired
	private SingleConditionalOrderPreviewStore previewStore;

	@Autowired
	private SingleConditionalOrderExecutionStore executionStore;

	/** 미리보기 하나를 한 번만 실행하고 조건 주문 식별값을 저장하는지 검사합니다. */
	@Test
	@DisplayName("단일 조건 주문 실행권을 데이터베이스에서 한 번만 만든다")
	void 단일_조건_주문_실행권을_데이터베이스에서_한_번만_만든다() {
		OffsetDateTime createdAt = OffsetDateTime.of(
				2026, 9, 7, 15, 0, 0, 0, ZoneOffset.ofHours(9));
		SingleConditionalOrderPreviewResponse preview = previewStore.save(
				미리보기를_만든다(createdAt));

		assertThat(previewStore.approvePending(preview.previewId(), createdAt.plusSeconds(1)))
				.isTrue();
		assertThat(previewStore.approvePending(preview.previewId(), createdAt.plusSeconds(2)))
				.isFalse();
		SingleConditionalOrderExecutionResponse first = 실행_준비_기록을_만든다(
				preview.previewId(), createdAt.plusSeconds(3));
		SingleConditionalOrderExecutionResponse duplicate = 실행_준비_기록을_만든다(
				preview.previewId(), createdAt.plusSeconds(4));

		assertThat(executionStore.claim(first)).isTrue();
		assertThat(executionStore.claim(duplicate)).isFalse();
		assertThat(previewStore.consumeApproved(preview.previewId(), createdAt.plusSeconds(5)))
				.isTrue();
		assertThat(executionStore.markSubmitting(first.executionId(), createdAt.plusSeconds(5)))
				.isTrue();
		assertThat(executionStore.markAccepted(
				first.executionId(), "created-conditional-order", createdAt.plusSeconds(6)))
				.isTrue();
		assertThat(executionStore.markAccepted(
				first.executionId(), "other-order", createdAt.plusSeconds(7)))
				.isFalse();

		SingleConditionalOrderExecutionResponse stored = executionStore
				.findByPreviewId(preview.previewId()).orElseThrow();
		assertThat(stored.status()).isEqualTo(OrderExecutionStatus.ACCEPTED);
		assertThat(stored.conditionalOrderId()).isEqualTo("created-conditional-order");
		assertThat(previewStore.findById(preview.previewId()).orElseThrow().status())
				.isEqualTo(OrderPreviewStatus.CONSUMED);
	}

	/** 데이터베이스 테스트에 사용할 승인 대기 단일 조건 주문 미리보기를 만듭니다. */
	private SingleConditionalOrderPreviewResponse 미리보기를_만든다(OffsetDateTime createdAt) {
		return new SingleConditionalOrderPreviewResponse(
				UUID.randomUUID().toString(), createdAt, createdAt.plusMinutes(2), 1L,
				"005930", ConditionalOrderType.SINGLE, OrderSide.BUY, OrderType.LIMIT,
				BigDecimal.TEN, new BigDecimal("72000"), new BigDecimal("71000"),
				LocalDate.parse("2026-09-10"), new BigDecimal("70000"),
				new BigDecimal("71000"), "KRW", "KR", new BigDecimal("0.001"),
				new BigDecimal("710000"), new BigDecimal("710"),
				new BigDecimal("710710"), false, false,
				OrderPreviewStatus.PENDING_APPROVAL, null);
	}

	/** 저장된 미리보기와 연결할 실행 준비 기록을 만듭니다. */
	private SingleConditionalOrderExecutionResponse 실행_준비_기록을_만든다(
			String previewId,
			OffsetDateTime createdAt) {
		return new SingleConditionalOrderExecutionResponse(
				UUID.randomUUID().toString(), previewId, UUID.randomUUID().toString(),
				null, "MOCK", OrderExecutionStatus.PREPARED, null,
				createdAt, createdAt, null, null);
	}
}
