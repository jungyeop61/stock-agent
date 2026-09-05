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

		boolean firstClaim = executionStore.claim(first, "a".repeat(64));
		boolean duplicateClaim = executionStore.claim(duplicate, "b".repeat(64));
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
		executionStore.claim(prepared, "c".repeat(64));

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
	 * 결과 불명 실행의 요청 지문을 읽고 복구권과 회수한 주문번호를 한 번만 기록하는지 검사합니다.
	 */
	@Test
	@DisplayName("결과 불명 실행을 데이터베이스에서 한 번만 복구한다")
	void 결과_불명_실행을_데이터베이스에서_한_번만_복구한다() {
		OffsetDateTime createdAt = OffsetDateTime.of(2026, 9, 6, 21, 0, 0, 0, ZoneOffset.ofHours(9));
		OrderPreviewResponse preview = 승인된_미리보기를_저장한다(createdAt);
		OrderExecutionResponse prepared = 실행_준비_기록을_만든다(
				preview.previewId(), createdAt.plusSeconds(10));
		String requestFingerprint = "d".repeat(64);
		executionStore.claim(prepared, requestFingerprint);
		OffsetDateTime submittedAt = createdAt.plusSeconds(11);
		executionStore.markSubmitting(prepared.executionId(), submittedAt);
		executionStore.markUnknown(prepared.executionId(), submittedAt.plusSeconds(1));

		OrderExecutionRecoveryCandidate candidate = executionStore
				.findRecoveryCandidateById(prepared.executionId()).orElseThrow();
		OffsetDateTime recoveryStartedAt = submittedAt.plusMinutes(9);
		boolean claimed = executionStore.claimRecovery(
				prepared.executionId(), recoveryStartedAt.minusMinutes(10), recoveryStartedAt);
		boolean duplicateClaim = executionStore.claimRecovery(
				prepared.executionId(), recoveryStartedAt.minusMinutes(10), recoveryStartedAt);
		boolean recovered = executionStore.markRecovered(
				prepared.executionId(), "mock-recovered-order", recoveryStartedAt.plusSeconds(1));
		OrderExecutionResponse stored = executionStore.findById(prepared.executionId()).orElseThrow();

		assertThat(candidate.requestFingerprint()).isEqualTo(requestFingerprint);
		assertThat(claimed).isTrue();
		assertThat(duplicateClaim).isFalse();
		assertThat(recovered).isTrue();
		assertThat(stored.status()).isEqualTo(OrderExecutionStatus.ACCEPTED);
		assertThat(stored.failureType()).isNull();
		assertThat(stored.brokerOrderId()).isEqualTo("mock-recovered-order");
		assertThat(stored.recoveryAttemptedAt()).isEqualTo(recoveryStartedAt);
	}

	/**
	 * 정확히 10분이 지났거나 한 번 실패한 복구는 데이터베이스 조건에서도 재선점하지 않는지 검사합니다.
	 */
	@Test
	@DisplayName("만료되거나 이미 실패한 복구권을 데이터베이스에서 차단한다")
	void 만료되거나_이미_실패한_복구권을_데이터베이스에서_차단한다() {
		OffsetDateTime createdAt = OffsetDateTime.of(2026, 9, 6, 22, 0, 0, 0, ZoneOffset.ofHours(9));
		OrderPreviewResponse expiredPreview = 승인된_미리보기를_저장한다(createdAt);
		OrderExecutionResponse expired = 실행_준비_기록을_만든다(
				expiredPreview.previewId(), createdAt.plusSeconds(1));
		executionStore.claim(expired, "e".repeat(64));
		OffsetDateTime expiredSubmittedAt = createdAt.plusSeconds(2);
		executionStore.markSubmitting(expired.executionId(), expiredSubmittedAt);
		executionStore.markUnknown(expired.executionId(), expiredSubmittedAt.plusSeconds(1));
		OffsetDateTime exactTenMinutes = expiredSubmittedAt.plusMinutes(10);

		boolean expiredClaim = executionStore.claimRecovery(
				expired.executionId(), exactTenMinutes.minusMinutes(10), exactTenMinutes);

		OrderPreviewResponse retriedPreview = 승인된_미리보기를_저장한다(createdAt.plusMinutes(20));
		OrderExecutionResponse retried = 실행_준비_기록을_만든다(
				retriedPreview.previewId(), createdAt.plusMinutes(20).plusSeconds(1));
		executionStore.claim(retried, "f".repeat(64));
		OffsetDateTime retriedSubmittedAt = createdAt.plusMinutes(20).plusSeconds(2);
		executionStore.markSubmitting(retried.executionId(), retriedSubmittedAt);
		executionStore.markUnknown(retried.executionId(), retriedSubmittedAt.plusSeconds(1));
		OffsetDateTime recoveryStartedAt = retriedSubmittedAt.plusMinutes(1);
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
				OrderExecutionStatus.PREPARED, null, null, createdAt, createdAt, null, null, null);
	}
}
