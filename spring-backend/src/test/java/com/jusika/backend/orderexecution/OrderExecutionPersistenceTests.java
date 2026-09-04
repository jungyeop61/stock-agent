package com.jusika.backend.orderexecution;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.jusika.backend.orderpreview.OrderPreviewResponse;
import com.jusika.backend.orderpreview.OrderPreviewStatus;
import com.jusika.backend.orderpreview.OrderPreviewStore;
import com.jusika.backend.orderpreview.OrderSide;
import com.jusika.backend.orderpreview.OrderType;

/**
 * 플라이웨이로 만든 실제 H2 테이블에서 실행권과 주문 상태의 원자적 변경을 검사합니다.
 */
@SpringBootTest
class OrderExecutionPersistenceTests {

	@Autowired
	private OrderPreviewStore previewStore;

	@Autowired
	private OrderExecutionStore executionStore;

	/**
	 * 하나의 승인 미리보기에는 실행권이 한 번만 생기고 접수 상태가 순서대로 저장되는지 검사합니다.
	 */
	@Test
	@DisplayName("하나의 주문 미리보기 실행권을 한 번만 만들고 접수 상태를 저장한다")
	void 하나의_주문_미리보기_실행권을_한_번만_만들고_접수_상태를_저장한다() {
		OffsetDateTime createdAt = OffsetDateTime.of(2026, 9, 4, 21, 0, 0, 0, ZoneOffset.ofHours(9));
		OrderPreviewResponse preview = 승인된_미리보기를_저장한다(createdAt);
		OrderExecutionResponse first = 실행_준비_기록을_만든다(preview.previewId(), createdAt.plusSeconds(10));
		OrderExecutionResponse duplicate = 실행_준비_기록을_만든다(preview.previewId(), createdAt.plusSeconds(11));

		boolean firstClaim = executionStore.claim(first);
		boolean duplicateClaim = executionStore.claim(duplicate);
		boolean submitting = executionStore.markSubmitting(first.executionId(), createdAt.plusSeconds(12));
		boolean accepted = executionStore.markAccepted(
				first.executionId(), "mock-order-id", createdAt.plusSeconds(13));
		boolean secondAcceptance = executionStore.markAccepted(
				first.executionId(), "other-order-id", createdAt.plusSeconds(14));
		OrderExecutionResponse stored = executionStore.findById(first.executionId()).orElseThrow();

		assertThat(firstClaim).isTrue();
		assertThat(duplicateClaim).isFalse();
		assertThat(submitting).isTrue();
		assertThat(accepted).isTrue();
		assertThat(secondAcceptance).isFalse();
		assertThat(stored.status()).isEqualTo(OrderExecutionStatus.ACCEPTED);
		assertThat(stored.brokerOrderId()).isEqualTo("mock-order-id");
		assertThat(stored.failureType()).isNull();
		assertThat(stored.submittedAt()).isEqualTo(createdAt.plusSeconds(12));
		assertThat(stored.completedAt()).isEqualTo(createdAt.plusSeconds(13));
	}

	/**
	 * 제출 전에 미리보기 상태가 어긋나면 준비 기록을 내부 오류로 안전하게 종료하는지 검사합니다.
	 */
	@Test
	@DisplayName("제출 전 내부 오류를 실행 준비 기록에 남긴다")
	void 제출_전_내부_오류를_실행_준비_기록에_남긴다() {
		OffsetDateTime createdAt = OffsetDateTime.of(2026, 9, 4, 22, 0, 0, 0, ZoneOffset.ofHours(9));
		OrderPreviewResponse preview = 승인된_미리보기를_저장한다(createdAt);
		OrderExecutionResponse prepared = 실행_준비_기록을_만든다(
				preview.previewId(), createdAt.plusSeconds(10));
		executionStore.claim(prepared);

		boolean failed = executionStore.markPreparationFailed(
				prepared.executionId(), createdAt.plusSeconds(11));
		boolean submitting = executionStore.markSubmitting(
				prepared.executionId(), createdAt.plusSeconds(12));
		OrderExecutionResponse stored = executionStore.findByPreviewId(preview.previewId()).orElseThrow();

		assertThat(failed).isTrue();
		assertThat(submitting).isFalse();
		assertThat(stored.status()).isEqualTo(OrderExecutionStatus.REJECTED);
		assertThat(stored.failureType()).isEqualTo(OrderExecutionFailureType.INTERNAL_STATE);
		assertThat(stored.completedAt()).isEqualTo(createdAt.plusSeconds(11));
	}

	/**
	 * 데이터베이스 테스트에 사용할 승인된 국내 지정가 매수 미리보기를 저장합니다.
	 *
	 * @param createdAt 미리보기 생성과 승인 시각
	 * @return 데이터베이스에 저장된 승인 미리보기
	 */
	private OrderPreviewResponse 승인된_미리보기를_저장한다(OffsetDateTime createdAt) {
		OrderPreviewResponse preview = new OrderPreviewResponse(
				UUID.randomUUID().toString(), createdAt, createdAt.plusMinutes(2), 1L,
				"005930", OrderSide.BUY, OrderType.LIMIT, BigDecimal.ONE,
				new BigDecimal("70000"), new BigDecimal("72000"), new BigDecimal("70000"),
				"KRW", "KR", new BigDecimal("0.00015"), new BigDecimal("70000"),
				new BigDecimal("10.5"), new BigDecimal("70010.5"), false, false, true,
				OrderPreviewStatus.APPROVED, createdAt);
		return previewStore.save(preview);
	}

	/**
	 * 주문 미리보기와 연결된 새 실행 준비 기록을 만듭니다.
	 *
	 * @param previewId 연결할 주문 미리보기 식별값
	 * @param createdAt 실행 준비 기록 생성 시각
	 * @return 아직 제출하지 않은 실행 준비 기록
	 */
	private OrderExecutionResponse 실행_준비_기록을_만든다(
			String previewId,
			OffsetDateTime createdAt) {
		return new OrderExecutionResponse(
				UUID.randomUUID().toString(), previewId, UUID.randomUUID().toString(), "MOCK",
				OrderExecutionStatus.PREPARED, null, null, createdAt, createdAt, null, null);
	}
}
