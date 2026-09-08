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

	/** 결과 불명 실행의 지문을 읽고 복구권과 회수한 주문번호를 한 번만 저장하는지 검사합니다. */
	@Test
	@DisplayName("결과 불명 금액 주문을 데이터베이스에서 한 번만 복구한다")
	void 결과_불명_금액_주문을_데이터베이스에서_한_번만_복구한다() {
		OffsetDateTime now = OffsetDateTime.of(2026, 9, 8, 12, 0, 0, 0, ZoneOffset.ofHours(9));
		AmountOrderPreviewResponse preview = 승인된_미리보기를_저장한다(now);
		AmountOrderExecutionResponse execution = 실행_준비_기록을_만든다(preview.previewId(), now);
		String fingerprint = "d".repeat(64);
		executionStore.claim(execution, fingerprint);
		OffsetDateTime submittedAt = now.plusSeconds(1);
		executionStore.markSubmitting(execution.executionId(), submittedAt);
		executionStore.markUnknown(execution.executionId(), submittedAt.plusSeconds(1));
		OffsetDateTime recoveryStartedAt = submittedAt.plusMinutes(9);

		AmountOrderExecutionRecoveryCandidate candidate = executionStore
				.findRecoveryCandidateById(execution.executionId()).orElseThrow();
		boolean claimed = executionStore.claimRecovery(
				execution.executionId(), recoveryStartedAt.minusMinutes(10), recoveryStartedAt);
		boolean duplicateClaim = executionStore.claimRecovery(
				execution.executionId(), recoveryStartedAt.minusMinutes(10), recoveryStartedAt);
		boolean recovered = executionStore.markRecovered(
				execution.executionId(), "mock-recovered-amount-order", recoveryStartedAt.plusSeconds(1));
		AmountOrderExecutionResponse stored = executionStore.findById(execution.executionId()).orElseThrow();

		assertThat(candidate.requestFingerprint()).isEqualTo(fingerprint);
		assertThat(claimed).isTrue();
		assertThat(duplicateClaim).isFalse();
		assertThat(recovered).isTrue();
		assertThat(stored.status()).isEqualTo(OrderExecutionStatus.ACCEPTED);
		assertThat(stored.failureType()).isNull();
		assertThat(stored.recoveryAttemptedAt()).isEqualTo(recoveryStartedAt);
		assertThat(stored.completedAt()).isEqualTo(recoveryStartedAt.plusSeconds(1));
	}

	/** 정확히 10분이 지난 실행과 한 번 실패한 복구는 다시 선점되지 않는지 검사합니다. */
	@Test
	@DisplayName("만료되거나 이미 실패한 금액 주문 복구권을 데이터베이스에서 차단한다")
	void 만료되거나_이미_실패한_금액_주문_복구권을_데이터베이스에서_차단한다() {
		OffsetDateTime now = OffsetDateTime.of(2026, 9, 8, 13, 0, 0, 0, ZoneOffset.ofHours(9));
		AmountOrderPreviewResponse expiredPreview = 승인된_미리보기를_저장한다(now);
		AmountOrderExecutionResponse expired = 실행_준비_기록을_만든다(expiredPreview.previewId(), now);
		executionStore.claim(expired, "e".repeat(64));
		executionStore.markSubmitting(expired.executionId(), now.plusSeconds(1));
		executionStore.markUnknown(expired.executionId(), now.plusSeconds(2));
		OffsetDateTime exactTenMinutes = now.plusSeconds(1).plusMinutes(10);

		boolean expiredClaim = executionStore.claimRecovery(
				expired.executionId(), exactTenMinutes.minusMinutes(10), exactTenMinutes);

		AmountOrderPreviewResponse retriedPreview = 승인된_미리보기를_저장한다(now.plusMinutes(20));
		AmountOrderExecutionResponse retried = 실행_준비_기록을_만든다(
				retriedPreview.previewId(), now.plusMinutes(20));
		executionStore.claim(retried, "f".repeat(64));
		OffsetDateTime submittedAt = now.plusMinutes(20).plusSeconds(1);
		executionStore.markSubmitting(retried.executionId(), submittedAt);
		executionStore.markUnknown(retried.executionId(), submittedAt.plusSeconds(1));
		OffsetDateTime recoveryStartedAt = submittedAt.plusMinutes(1);
		boolean firstClaim = executionStore.claimRecovery(
				retried.executionId(), recoveryStartedAt.minusMinutes(10), recoveryStartedAt);
		boolean markedUnknown = executionStore.markRecoveryUnknown(
				retried.executionId(), recoveryStartedAt.plusSeconds(1));
		boolean secondClaim = executionStore.claimRecovery(
				retried.executionId(), recoveryStartedAt.minusMinutes(9), recoveryStartedAt.plusMinutes(1));

		assertThat(expiredClaim).isFalse();
		assertThat(firstClaim).isTrue();
		assertThat(markedUnknown).isTrue();
		assertThat(secondClaim).isFalse();
		assertThat(executionStore.findById(retried.executionId()).orElseThrow().failureType())
				.isEqualTo(OrderExecutionFailureType.RECOVERY_UNKNOWN);
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
				OrderExecutionStatus.PREPARED, null, null, now, now, null, null, null);
	}
}
