package com.jusika.backend.amountorderexecution;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.jusika.backend.amountorderpreview.AmountOrderPreviewResponse;
import com.jusika.backend.amountorderpreview.AmountOrderPreviewStore;
import com.jusika.backend.orderexecution.OrderExecutionFailureType;
import com.jusika.backend.orderexecution.OrderExecutionStatus;
import com.jusika.backend.orderpreview.OrderPreviewStatus;
import com.jusika.backend.orderpreview.OrderSide;
import com.jusika.backend.orderpreview.OrderType;

/**
 * 플라이웨이 V13이 만든 H2 테이블에서 금액 주문 실행권과 상태 전이를 검사합니다.
 */
@SpringBootTest
class AmountOrderExecutionPersistenceTests {

	@Autowired
	private AmountOrderPreviewStore previewStore;

	@Autowired
	private AmountOrderExecutionStore executionStore;

	/** 하나의 금액 미리보기에 실행 기록이 한 번만 만들어지는지 검사합니다. */
	@Test
	@DisplayName("금액 주문 미리보기 실행권을 한 번만 만들고 접수 상태를 저장한다")
	void 금액_주문_미리보기_실행권을_한_번만_만들고_접수_상태를_저장한다() {
		OffsetDateTime now = OffsetDateTime.of(2026, 9, 8, 10, 0, 0, 0, ZoneOffset.ofHours(9));
		AmountOrderPreviewResponse preview = 승인된_미리보기를_저장한다(now);
		AmountOrderExecutionResponse first = 실행_준비_기록을_만든다(preview.previewId(), now);
		AmountOrderExecutionResponse duplicate = 실행_준비_기록을_만든다(
				preview.previewId(), now.plusSeconds(1));

		boolean firstClaim = executionStore.claim(first, "a".repeat(64));
		boolean duplicateClaim = executionStore.claim(duplicate, "b".repeat(64));
		boolean consumed = previewStore.consumeApproved(preview.previewId(), now.plusSeconds(2));
		boolean submitting = executionStore.markSubmitting(first.executionId(), now.plusSeconds(2));
		boolean accepted = executionStore.markAccepted(
				first.executionId(), "mock-amount-order", now.plusSeconds(3));
		boolean secondAcceptance = executionStore.markAccepted(
				first.executionId(), "other-order", now.plusSeconds(4));
		AmountOrderExecutionResponse stored = executionStore.findById(first.executionId()).orElseThrow();

		assertThat(firstClaim).isTrue();
		assertThat(duplicateClaim).isFalse();
		assertThat(consumed).isTrue();
		assertThat(submitting).isTrue();
		assertThat(accepted).isTrue();
		assertThat(secondAcceptance).isFalse();
		assertThat(stored.status()).isEqualTo(OrderExecutionStatus.ACCEPTED);
		assertThat(stored.brokerOrderId()).isEqualTo("mock-amount-order");
		assertThat(previewStore.findById(preview.previewId()).orElseThrow().status())
				.isEqualTo(OrderPreviewStatus.CONSUMED);
	}

	/** 제출 결과가 불명확하면 완료 시각 없이 UNKNOWN 상태가 저장되는지 검사합니다. */
	@Test
	@DisplayName("금액 주문 결과 불명 상태를 자동 재시도 없이 저장한다")
	void 금액_주문_결과_불명_상태를_자동_재시도_없이_저장한다() {
		OffsetDateTime now = OffsetDateTime.of(2026, 9, 8, 11, 0, 0, 0, ZoneOffset.ofHours(9));
		AmountOrderPreviewResponse preview = 승인된_미리보기를_저장한다(now);
		AmountOrderExecutionResponse execution = 실행_준비_기록을_만든다(preview.previewId(), now);
		executionStore.claim(execution, "c".repeat(64));
		executionStore.markSubmitting(execution.executionId(), now.plusSeconds(1));

		boolean unknown = executionStore.markUnknown(execution.executionId(), now.plusSeconds(2));
		boolean duplicateUnknown = executionStore.markUnknown(
				execution.executionId(), now.plusSeconds(3));
		AmountOrderExecutionResponse stored = executionStore
				.findByPreviewId(preview.previewId()).orElseThrow();

		assertThat(unknown).isTrue();
		assertThat(duplicateUnknown).isFalse();
		assertThat(stored.status()).isEqualTo(OrderExecutionStatus.UNKNOWN);
		assertThat(stored.failureType()).isEqualTo(OrderExecutionFailureType.SUBMISSION_UNKNOWN);
		assertThat(stored.completedAt()).isNull();
	}

	/** 데이터베이스 테스트에 사용할 승인된 정상 금액 주문 미리보기를 저장합니다. */
	private AmountOrderPreviewResponse 승인된_미리보기를_저장한다(OffsetDateTime now) {
		return previewStore.save(new AmountOrderPreviewResponse(
				UUID.randomUUID().toString(), now.minusMinutes(1), now.plusMinutes(1), 1L,
				"AAPL", OrderSide.BUY, OrderType.MARKET, new BigDecimal("100"), "USD", "US",
				new BigDecimal("200"), new BigDecimal("0.5"), new BigDecimal("0.001"),
				new BigDecimal("0.1"), new BigDecimal("100.1"), new BigDecimal("1400"),
				now.minusMinutes(2), now.plusMinutes(2), new BigDecimal("140000"),
				false, true, OrderPreviewStatus.APPROVED, now.minusSeconds(30)));
	}

	/** 지정한 금액 미리보기에 연결할 새 실행 준비 기록을 만듭니다. */
	private AmountOrderExecutionResponse 실행_준비_기록을_만든다(
			String previewId,
			OffsetDateTime now) {
		return new AmountOrderExecutionResponse(
				UUID.randomUUID().toString(), previewId, UUID.randomUUID().toString(), "MOCK",
				OrderExecutionStatus.PREPARED, null, null, now, now, null, null);
	}
}
