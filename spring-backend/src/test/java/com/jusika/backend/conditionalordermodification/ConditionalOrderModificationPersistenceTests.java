package com.jusika.backend.conditionalordermodification;

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
import com.jusika.backend.conditionalordermodification.ConditionalOrderModificationPreviewResponse.OriginalCondition;
import com.jusika.backend.conditionalordermodification.ConditionalOrderModificationPreviewResponse.RequestedCondition;
import com.jusika.backend.orderexecution.OrderExecutionStatus;
import com.jusika.backend.orderpreview.OrderSide;
import com.jusika.backend.orderpreview.OrderType;

/** V10 테이블에서 원주문별 중복 정정 방지와 대체 식별값 저장을 검사합니다. */
@SpringBootTest
class ConditionalOrderModificationPersistenceTests {

	@Autowired private ConditionalOrderModificationPreviewStore previewStore;
	@Autowired private ConditionalOrderModificationExecutionStore executionStore;

	/** 같은 원조건 주문에는 첫 실행권만 만들고 성공 시 새 식별값을 저장하는지 검사합니다. */
	@Test
	@DisplayName("같은 원조건 주문의 정정 실행권을 한 번만 만들고 새 식별값을 저장한다")
	void 같은_원조건_주문의_정정_실행권을_한_번만_만들고_새_식별값을_저장한다() {
		OffsetDateTime now = OffsetDateTime.of(2026, 9, 7, 21, 0, 0, 0, ZoneOffset.ofHours(9));
		String originalId = "original-" + UUID.randomUUID();
		ConditionalOrderModificationPreviewResponse firstPreview = 미리보기를_저장한다(originalId, now);
		ConditionalOrderModificationPreviewResponse secondPreview = 미리보기를_저장한다(originalId, now.plusSeconds(1));
		assertThat(previewStore.approvePending(firstPreview.previewId(), now.plusSeconds(2))).isTrue();
		assertThat(previewStore.consumeApproved(firstPreview.previewId(), now.plusSeconds(3))).isTrue();

		ConditionalOrderModificationExecutionResponse first = 실행_준비_기록을_만든다(
				firstPreview.previewId(), originalId, now.plusSeconds(3));
		ConditionalOrderModificationExecutionResponse duplicate = 실행_준비_기록을_만든다(
				secondPreview.previewId(), originalId, now.plusSeconds(4));
		assertThat(executionStore.claim(first)).isTrue();
		assertThat(executionStore.claim(duplicate)).isFalse();
		assertThat(executionStore.markSubmitting(first.executionId(), now.plusSeconds(5))).isTrue();
		assertThat(executionStore.markAccepted(
				first.executionId(), "replacement-id", now.plusSeconds(6))).isTrue();

		ConditionalOrderModificationExecutionResponse stored = executionStore
				.findByTarget(1L, originalId).orElseThrow();
		assertThat(stored.status()).isEqualTo(OrderExecutionStatus.ACCEPTED);
		assertThat(stored.replacementConditionalOrderId()).isEqualTo("replacement-id");
		assertThat(stored.originalConditionalOrderId()).isEqualTo(originalId);
	}

	/** 데이터베이스 테스트에 사용할 유형 전환 정정 미리보기를 저장합니다. */
	private ConditionalOrderModificationPreviewResponse 미리보기를_저장한다(
			String originalId, OffsetDateTime now) {
		OriginalCondition original = new OriginalCondition(
				ConditionalOrderConditionType.STOP, ConditionalOrderConditionStatus.WATCHING,
				new BigDecimal("200"), null, new BigDecimal("199"), null);
		RequestedCondition first = new RequestedCondition(
				OrderSide.SELL, new BigDecimal("210"), new BigDecimal("209"));
		RequestedCondition second = new RequestedCondition(
				OrderSide.SELL, new BigDecimal("190"), new BigDecimal("189"));
		return previewStore.save(new ConditionalOrderModificationPreviewResponse(
				UUID.randomUUID().toString(), now, now.plusMinutes(2), 1L, originalId,
				ConditionalOrderType.SINGLE, ConditionalOrderStatus.WATCHING, "AAPL",
				ConditionalOrderMarket.US, BigDecimal.TEN, OrderType.LIMIT,
				LocalDate.of(2026, 9, 9), original, null, now.minusMinutes(5),
				ConditionalOrderType.OCO, BigDecimal.TEN, OrderType.LIMIT,
				LocalDate.of(2026, 9, 10), first, second, new BigDecimal("200"), "USD",
				false, ConditionalOrderModificationPreviewStatus.PENDING_APPROVAL, null));
	}

	/** 정정 실행권 선점 테스트에 사용할 준비 상태 실행 기록을 만듭니다. */
	private ConditionalOrderModificationExecutionResponse 실행_준비_기록을_만든다(
			String previewId, String originalId, OffsetDateTime now) {
		return new ConditionalOrderModificationExecutionResponse(
				UUID.randomUUID().toString(), previewId, 1L, originalId, null, "MOCK",
				OrderExecutionStatus.PREPARED, null, now, now, null, null);
	}
}
