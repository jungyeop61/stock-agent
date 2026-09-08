package com.jusika.backend.amountorderpreview;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.jusika.backend.orderpreview.OrderPreviewStatus;
import com.jusika.backend.orderpreview.OrderSide;
import com.jusika.backend.orderpreview.OrderType;

/**
 * Flyway V11로 만든 H2 테이블에서 달러 금액 주문 미리보기 전체 값의 저장과 조회를 검사합니다.
 */
@SpringBootTest
class AmountOrderPreviewPersistenceTests {

	@Autowired
	private AmountOrderPreviewStore previewStore;

	/**
	 * 금액 주문 미리보기의 달러 계산값과 환율 사본을 손실 없이 저장하는지 검사합니다.
	 */
	@Test
	@DisplayName("금액 주문 미리보기 전체 계산 결과를 데이터베이스에 저장한다")
	void 금액_주문_미리보기_전체_계산_결과를_데이터베이스에_저장한다() {
		OffsetDateTime createdAt = OffsetDateTime.of(
				2026, 9, 8, 9, 30, 30, 0, ZoneOffset.ofHours(9));
		AmountOrderPreviewResponse preview = new AmountOrderPreviewResponse(
				UUID.randomUUID().toString(),
				createdAt,
				createdAt.plusMinutes(2),
				1L,
				"AAPL",
				OrderSide.BUY,
				OrderType.MARKET,
				new BigDecimal("100.1234567890123456789012345678"),
				"USD",
				"US",
				new BigDecimal("200.25"),
				new BigDecimal("0.5"),
				new BigDecimal("0.001"),
				new BigDecimal("0.100123456789012346"),
				new BigDecimal("100.2235802458013580249012345678"),
				new BigDecimal("1400.5"),
				createdAt.minusSeconds(30),
				createdAt.plusSeconds(30),
				new BigDecimal("140223.401234567890123456"),
				false,
				true,
				OrderPreviewStatus.PENDING_APPROVAL);

		AmountOrderPreviewResponse saved = previewStore.save(preview);
		AmountOrderPreviewResponse found = previewStore.findById(preview.previewId()).orElseThrow();

		assertThat(saved.previewId()).isEqualTo(preview.previewId());
		assertThat(found.createdAt()).isEqualTo(createdAt);
		assertThat(found.expiresAt()).isEqualTo(createdAt.plusMinutes(2));
		assertThat(found.accountSeq()).isEqualTo(1L);
		assertThat(found.symbol()).isEqualTo("AAPL");
		assertThat(found.side()).isEqualTo(OrderSide.BUY);
		assertThat(found.orderType()).isEqualTo(OrderType.MARKET);
		assertThat(found.orderAmount()).isEqualByComparingTo(preview.orderAmount());
		assertThat(found.referencePrice()).isEqualByComparingTo("200.25");
		assertThat(found.estimatedQuantity()).isEqualByComparingTo("0.5");
		assertThat(found.estimatedCommission())
				.isEqualByComparingTo(preview.estimatedCommission());
		assertThat(found.estimatedTotalCost()).isEqualByComparingTo(preview.estimatedTotalCost());
		assertThat(found.exchangeRate()).isEqualByComparingTo("1400.5");
		assertThat(found.exchangeRateValidFrom()).isEqualTo(createdAt.minusSeconds(30));
		assertThat(found.exchangeRateValidUntil()).isEqualTo(createdAt.plusSeconds(30));
		assertThat(found.estimatedOrderAmountKrw())
				.isEqualByComparingTo(preview.estimatedOrderAmountKrw());
		assertThat(found.status()).isEqualTo(OrderPreviewStatus.PENDING_APPROVAL);
	}
}
