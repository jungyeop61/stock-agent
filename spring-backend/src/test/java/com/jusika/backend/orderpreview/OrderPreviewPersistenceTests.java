package com.jusika.backend.orderpreview;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 플라이웨이로 만든 실제 H2 테이블에서 주문 미리보기 저장과 원자적 상태 변경을 검사합니다.
 */
@SpringBootTest
class OrderPreviewPersistenceTests {

	@Autowired
	private OrderPreviewStore previewStore;

	/**
	 * 저장된 승인 대기 미리보기는 한 번만 승인되고 계산 내용은 유지되는지 검사합니다.
	 */
	@Test
	@DisplayName("데이터베이스에 저장한 주문 미리보기를 한 번만 승인한다")
	void 데이터베이스에_저장한_주문_미리보기를_한_번만_승인한다() {
		OffsetDateTime createdAt = OffsetDateTime.of(2026, 9, 4, 20, 0, 0, 0, ZoneOffset.ofHours(9));
		OrderPreviewResponse pending = 승인_대기_미리보기를_만든다(createdAt, createdAt.plusMinutes(2));
		previewStore.save(pending);
		OffsetDateTime approvedAt = createdAt.plusSeconds(30);

		boolean firstApproval = previewStore.approvePending(pending.previewId(), approvedAt);
		boolean secondApproval = previewStore.approvePending(pending.previewId(), approvedAt.plusSeconds(1));
		OrderPreviewResponse stored = previewStore.findById(pending.previewId()).orElseThrow();

		assertThat(firstApproval).isTrue();
		assertThat(secondApproval).isFalse();
		assertThat(stored.status()).isEqualTo(OrderPreviewStatus.APPROVED);
		assertThat(stored.approvedAt()).isEqualTo(approvedAt);
		assertThat(stored.symbol()).isEqualTo(pending.symbol());
		assertThat(stored.quantity()).isEqualByComparingTo(pending.quantity());
		assertThat(stored.estimatedAmountAfterCommission())
				.isEqualByComparingTo(pending.estimatedAmountAfterCommission());
	}

	/**
	 * 유효시간이 지난 승인 대기 미리보기만 데이터베이스에서 만료시키는지 검사합니다.
	 */
	@Test
	@DisplayName("데이터베이스에서 유효시간이 지난 주문 미리보기를 만료시킨다")
	void 데이터베이스에서_유효시간이_지난_주문_미리보기를_만료시킨다() {
		OffsetDateTime createdAt = OffsetDateTime.of(2026, 9, 4, 20, 0, 0, 0, ZoneOffset.ofHours(9));
		OrderPreviewResponse pending = 승인_대기_미리보기를_만든다(createdAt, createdAt.plusMinutes(2));
		previewStore.save(pending);

		boolean expired = previewStore.expirePending(pending.previewId(), createdAt.plusMinutes(2));
		boolean approved = previewStore.approvePending(pending.previewId(), createdAt.plusMinutes(1));
		OrderPreviewResponse stored = previewStore.findById(pending.previewId()).orElseThrow();

		assertThat(expired).isTrue();
		assertThat(approved).isFalse();
		assertThat(stored.status()).isEqualTo(OrderPreviewStatus.EXPIRED);
		assertThat(stored.approvedAt()).isNull();
	}

	/**
	 * 데이터베이스 검증에 사용할 승인 대기 주문 미리보기 전체 값을 만듭니다.
	 *
	 * @param createdAt 생성 시각
	 * @param expiresAt 승인 만료 시각
	 * @return 저장 가능한 승인 대기 주문 미리보기
	 */
	private OrderPreviewResponse 승인_대기_미리보기를_만든다(
			OffsetDateTime createdAt,
			OffsetDateTime expiresAt) {
		return new OrderPreviewResponse(
				UUID.randomUUID().toString(),
				createdAt,
				expiresAt,
				1L,
				"005930",
				OrderSide.BUY,
				OrderType.LIMIT,
				BigDecimal.ONE,
				new BigDecimal("70000"),
				new BigDecimal("72000"),
				new BigDecimal("70000"),
				"KRW",
				"KR",
				new BigDecimal("0.00015"),
				new BigDecimal("70000"),
				new BigDecimal("10.5"),
				new BigDecimal("70010.5"),
				false,
				false,
				true,
				OrderPreviewStatus.PENDING_APPROVAL,
				null);
	}
}
