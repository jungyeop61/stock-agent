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

/** 플라이웨이 V7 테이블에서 OCO 승인·중복 방지·실행 상태 저장을 검사합니다. */
@SpringBootTest
class OcoConditionalOrderPersistenceTests {

	@Autowired
	private OcoConditionalOrderPreviewStore previewStore;

	@Autowired
	private OcoConditionalOrderExecutionStore executionStore;

	/** OCO 미리보기 하나의 실행권을 한 번만 만들고 접수 식별값을 저장하는지 검사합니다. */
	@Test
	@DisplayName("OCO 실행권을 데이터베이스에서 한 번만 만든다")
	void OCO_실행권을_데이터베이스에서_한_번만_만든다() {
		OffsetDateTime createdAt = OffsetDateTime.of(
				2026, 9, 7, 15, 0, 0, 0, ZoneOffset.ofHours(9));
		OcoConditionalOrderPreviewResponse preview = previewStore.save(미리보기를_만든다(createdAt));

		assertThat(previewStore.approvePending(preview.previewId(), createdAt.plusSeconds(1)))
				.isTrue();
		assertThat(previewStore.approvePending(preview.previewId(), createdAt.plusSeconds(2)))
				.isFalse();
		OcoConditionalOrderExecutionResponse first = 실행_준비_기록을_만든다(
				preview.previewId(), createdAt.plusSeconds(3));
		OcoConditionalOrderExecutionResponse duplicate = 실행_준비_기록을_만든다(
				preview.previewId(), createdAt.plusSeconds(4));

		assertThat(executionStore.claim(first)).isTrue();
		assertThat(executionStore.claim(duplicate)).isFalse();
		assertThat(previewStore.consumeApproved(preview.previewId(), createdAt.plusSeconds(5)))
				.isTrue();
		assertThat(executionStore.markSubmitting(first.executionId(), createdAt.plusSeconds(5)))
				.isTrue();
		assertThat(executionStore.markAccepted(
				first.executionId(), "created-oco-order", createdAt.plusSeconds(6))).isTrue();
		assertThat(executionStore.markAccepted(
				first.executionId(), "other-order", createdAt.plusSeconds(7))).isFalse();

		OcoConditionalOrderExecutionResponse stored = executionStore
				.findByPreviewId(preview.previewId()).orElseThrow();
		assertThat(stored.status()).isEqualTo(OrderExecutionStatus.ACCEPTED);
		assertThat(stored.conditionalOrderId()).isEqualTo("created-oco-order");
		assertThat(previewStore.findById(preview.previewId()).orElseThrow().status())
				.isEqualTo(OrderPreviewStatus.CONSUMED);
	}

	/** 데이터베이스 테스트에 사용할 승인 대기 OCO 미리보기를 만듭니다. */
	private OcoConditionalOrderPreviewResponse 미리보기를_만든다(OffsetDateTime createdAt) {
		return new OcoConditionalOrderPreviewResponse(
				UUID.randomUUID().toString(), createdAt, createdAt.plusMinutes(2), 1L,
				"005930", ConditionalOrderType.OCO, BigDecimal.TEN, OrderType.LIMIT,
				LocalDate.parse("2026-09-10"), new BigDecimal("70000"), "KRW", "KR",
				new BigDecimal("0.001"),
				new OcoConditionalOrderPreviewResponse.Condition(
						OrderSide.SELL, new BigDecimal("80000"), new BigDecimal("79000"),
						new BigDecimal("790000"), new BigDecimal("790"),
						new BigDecimal("789210")),
				new OcoConditionalOrderPreviewResponse.Condition(
						OrderSide.SELL, new BigDecimal("65000"), new BigDecimal("64900"),
						new BigDecimal("649000"), new BigDecimal("649"),
						new BigDecimal("648351")),
				true, false, OrderPreviewStatus.PENDING_APPROVAL, null);
	}

	/** 저장된 OCO 미리보기와 연결할 실행 준비 기록을 만듭니다. */
	private OcoConditionalOrderExecutionResponse 실행_준비_기록을_만든다(
			String previewId,
			OffsetDateTime createdAt) {
		return new OcoConditionalOrderExecutionResponse(
				UUID.randomUUID().toString(), previewId, UUID.randomUUID().toString(),
				null, "MOCK", OrderExecutionStatus.PREPARED, null,
				createdAt, createdAt, null, null);
	}
}
