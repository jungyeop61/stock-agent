package com.jusika.backend.brokersafety;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.jusika.backend.conditionalorder.ConditionalOrderDetailResponse;
import com.jusika.backend.conditionalorder.ConditionalOrderListResponse;
import com.jusika.backend.conditionalorder.ConditionalOrderListStatus;
import com.jusika.backend.orderhistory.OrderDetailResponse;
import com.jusika.backend.orderhistory.OrderListResponse;
import com.jusika.backend.orderhistory.OrderListStatus;
import com.jusika.backend.toss.conditionalorder.TossConditionalOrderClient;
import com.jusika.backend.toss.orderhistory.TossOrderHistoryClient;

/** 실제 네트워크 없이 일반·조건 활성 주문 합산과 LIVE 주문 여유 판정을 검사합니다. */
class BrokerLiveOpenOrderCapacityServiceTests {

	private static final long ACCOUNT_SEQ = 1L;

	/** 0인 기본 상한은 토스 목록을 조회하기 전 모든 LIVE 주문을 차단하는지 검사합니다. */
	@Test
	@DisplayName("LIVE 활성 주문 개수 한도가 미설정이면 목록 조회 전에 차단한다")
	void LIVE_활성_주문_개수_한도가_미설정이면_목록_조회_전에_차단한다() {
		RecordingOrderHistoryClient orderClient = new RecordingOrderHistoryClient();
		RecordingConditionalOrderClient conditionalClient = new RecordingConditionalOrderClient();
		BrokerLiveOpenOrderCapacityService service = 서비스를_만든다(
				0, 0, orderClient, conditionalClient);

		assertThatThrownBy(() -> service.requireCapacity(
				ACCOUNT_SEQ, "005930", BrokerOpenOrderCapacityOperation.CREATE))
				.isInstanceOf(BrokerMutationBlockedException.class)
				.hasMessage("LIVE 활성 주문 개수 한도가 설정되어 있지 않습니다.");
		assertThat(orderClient.callCount).isZero();
		assertThat(conditionalClient.callCount).isZero();
	}

	/** 일반 주문과 조건 주문의 합계에 신규 주문 한 건을 더해 계좌 상한을 검사합니다. */
	@Test
	@DisplayName("일반 주문과 조건 주문을 합산해 계좌 활성 주문 상한을 차단한다")
	void 일반_주문과_조건_주문을_합산해_계좌_활성_주문_상한을_차단한다() {
		RecordingOrderHistoryClient orderClient = new RecordingOrderHistoryClient();
		orderClient.response = 일반_목록("000001");
		RecordingConditionalOrderClient conditionalClient = new RecordingConditionalOrderClient();
		conditionalClient.responses.add(조건_목록(null, false, "005930"));
		BrokerLiveOpenOrderCapacityService service = 서비스를_만든다(
				2, 2, orderClient, conditionalClient);

		assertThatThrownBy(() -> service.requireCapacity(
				ACCOUNT_SEQ, "005930", BrokerOpenOrderCapacityOperation.CREATE))
				.isInstanceOf(BrokerMutationBlockedException.class)
				.hasMessage("계좌의 활성 주문 개수가 안전 한도를 초과합니다.")
				.hasMessageNotContaining("2");
	}

	/** 같은 종목의 일반·조건 주문을 합산해 종목별 상한을 검사합니다. */
	@Test
	@DisplayName("일반 주문과 조건 주문을 합산해 동일 종목 상한을 차단한다")
	void 일반_주문과_조건_주문을_합산해_동일_종목_상한을_차단한다() {
		RecordingOrderHistoryClient orderClient = new RecordingOrderHistoryClient();
		orderClient.response = 일반_목록("005930");
		RecordingConditionalOrderClient conditionalClient = new RecordingConditionalOrderClient();
		conditionalClient.responses.add(조건_목록(null, false, "005930"));
		BrokerLiveOpenOrderCapacityService service = 서비스를_만든다(
				10, 2, orderClient, conditionalClient);

		assertThatThrownBy(() -> service.requireCapacity(
				ACCOUNT_SEQ, "005930", BrokerOpenOrderCapacityOperation.CREATE))
				.isInstanceOf(BrokerMutationBlockedException.class)
				.hasMessage("동일 종목의 활성 주문 개수가 안전 한도를 초과합니다.")
				.hasMessageNotContaining("005930");
	}

	/** 정정과 동일 요청 복구는 새 주문을 더하지 않으므로 현재 수가 상한과 같아도 통과하는지 검사합니다. */
	@Test
	@DisplayName("정정과 복구는 현재 활성 주문 수가 상한과 같으면 통과한다")
	void 정정과_복구는_현재_활성_주문_수가_상한과_같으면_통과한다() {
		RecordingOrderHistoryClient orderClient = new RecordingOrderHistoryClient();
		orderClient.response = 일반_목록("005930");
		RecordingConditionalOrderClient conditionalClient = new RecordingConditionalOrderClient();
		conditionalClient.responses.add(조건_목록(null, false, "005930"));
		BrokerLiveOpenOrderCapacityService service = 서비스를_만든다(
				2, 2, orderClient, conditionalClient);

		assertThatCode(() -> service.requireCapacity(
				ACCOUNT_SEQ, "005930", BrokerOpenOrderCapacityOperation.REPLACE_OR_RECOVER))
				.doesNotThrowAnyException();
	}

	/** 진행 중 조건 주문의 다음 페이지를 모두 따라가 최종 합계를 계산하는지 검사합니다. */
	@Test
	@DisplayName("진행 중 조건 주문의 모든 페이지를 합산한다")
	void 진행_중_조건_주문의_모든_페이지를_합산한다() {
		RecordingOrderHistoryClient orderClient = new RecordingOrderHistoryClient();
		orderClient.response = 일반_목록("000001");
		RecordingConditionalOrderClient conditionalClient = new RecordingConditionalOrderClient();
		conditionalClient.responses.add(조건_목록("next-page", true, "005930"));
		conditionalClient.responses.add(조건_목록(null, false, "000002"));
		BrokerLiveOpenOrderCapacityService service = 서비스를_만든다(
				4, 2, orderClient, conditionalClient);

		assertThatCode(() -> service.requireCapacity(
				ACCOUNT_SEQ, "005930", BrokerOpenOrderCapacityOperation.CREATE))
				.doesNotThrowAnyException();
		assertThat(conditionalClient.callCount).isEqualTo(2);
		assertThat(conditionalClient.requestedCursors).containsExactly(null, "next-page");
	}

	/** 일반 또는 조건 주문 목록을 확정할 수 없으면 실제 변경을 진행하지 않도록 차단하는지 검사합니다. */
	@Test
	@DisplayName("활성 주문 목록 조회 실패는 민감정보 없이 안전하게 차단한다")
	void 활성_주문_목록_조회_실패는_민감정보_없이_안전하게_차단한다() {
		RecordingOrderHistoryClient orderClient = new RecordingOrderHistoryClient();
		orderClient.failure = new IllegalStateException("외부 상세 오류");
		RecordingConditionalOrderClient conditionalClient = new RecordingConditionalOrderClient();
		BrokerLiveOpenOrderCapacityService service = 서비스를_만든다(
				10, 3, orderClient, conditionalClient);

		assertThatThrownBy(() -> service.requireCapacity(
				ACCOUNT_SEQ, "005930", BrokerOpenOrderCapacityOperation.CREATE))
				.isInstanceOf(BrokerMutationBlockedException.class)
				.hasMessage("활성 주문 수를 확인할 수 없어 실제 주문을 차단했습니다.")
				.hasMessageNotContaining("외부 상세 오류")
				.hasMessageNotContaining("005930");
	}

	/** 지정한 상한과 기록용 클라이언트로 활성 주문 여유 검사 서비스를 만듭니다. */
	private BrokerLiveOpenOrderCapacityService 서비스를_만든다(
			int accountLimit,
			int instrumentLimit,
			TossOrderHistoryClient orderClient,
			TossConditionalOrderClient conditionalClient) {
		BrokerSafetyProperties properties = new BrokerSafetyProperties(
				BrokerExecutionMode.LIVE, true, false,
				BrokerLiveAdapterProperties.allDisabled(), Set.of(ACCOUNT_SEQ),
				Set.of("KR:005930"), BrokerLiveOrderLimitProperties.allDisabled(),
				BrokerLiveDailyOrderLimitProperties.allDisabled(),
				new BrokerLiveOpenOrderLimitProperties(accountLimit, instrumentLimit));
		return new BrokerLiveOpenOrderCapacityService(properties, orderClient, conditionalClient);
	}

	/** 종목 코드들로 진행 중 일반 주문 목록 응답을 만듭니다. */
	private OrderListResponse 일반_목록(String... symbols) {
		List<OrderDetailResponse> orders = java.util.Arrays.stream(symbols)
				.map(this::일반_주문)
				.toList();
		return new OrderListResponse(
				ACCOUNT_SEQ, OrderListStatus.OPEN, null, null, null,
				orders, null, false);
	}

	/** 다음 페이지 정보와 종목 코드들로 진행 중 조건 주문 목록 응답을 만듭니다. */
	private ConditionalOrderListResponse 조건_목록(
			String nextCursor,
			boolean hasNext,
			String... symbols) {
		List<ConditionalOrderDetailResponse> orders = java.util.Arrays.stream(symbols)
				.map(this::조건_주문)
				.toList();
		return new ConditionalOrderListResponse(
				ACCOUNT_SEQ, ConditionalOrderListStatus.OPEN, null,
				orders, nextCursor, hasNext);
	}

	/** 활성 주문 개수 계산에 필요한 최소 일반 주문 응답을 만듭니다. */
	private OrderDetailResponse 일반_주문(String symbol) {
		return new OrderDetailResponse(
				ACCOUNT_SEQ, "test-order", symbol, null, null, null, null, null,
				null, null, null, null, null, null, null);
	}

	/** 활성 주문 개수 계산에 필요한 최소 조건 주문 응답을 만듭니다. */
	private ConditionalOrderDetailResponse 조건_주문(String symbol) {
		return new ConditionalOrderDetailResponse(
				ACCOUNT_SEQ, "test-conditional-order", null, null, symbol, null,
				null, null, null, null, null, null);
	}

	/** 실제 HTTP 없이 진행 중 일반 주문 목록 호출을 기록합니다. */
	private static final class RecordingOrderHistoryClient extends TossOrderHistoryClient {

		private OrderListResponse response = new OrderListResponse(
				ACCOUNT_SEQ, OrderListStatus.OPEN, null, null, null, List.of(), null, false);
		private RuntimeException failure;
		private int callCount;

		/** REST 의존성 없이 기록용 일반 주문 클라이언트를 초기화합니다. */
		private RecordingOrderHistoryClient() {
			super(null, null);
		}

		/** 네트워크 대신 준비한 진행 중 일반 주문 목록이나 오류를 반환합니다. */
		@Override
		public OrderListResponse getOrders(
				long accountSeq,
				OrderListStatus status,
				String symbol,
				java.time.LocalDate from,
				java.time.LocalDate to,
				String cursor,
				Integer limit) {
			callCount++;
			if (failure != null) {
				throw failure;
			}
			return response;
		}
	}

	/** 실제 HTTP 없이 진행 중 조건 주문 페이지 호출을 기록합니다. */
	private static final class RecordingConditionalOrderClient extends TossConditionalOrderClient {

		private final List<ConditionalOrderListResponse> responses = new ArrayList<>();
		private final List<String> requestedCursors = new ArrayList<>();
		private int callCount;

		/** REST 의존성 없이 기록용 조건 주문 클라이언트를 초기화합니다. */
		private RecordingConditionalOrderClient() {
			super(null, null);
		}

		/** 네트워크 대신 호출 순서에 맞춰 준비한 조건 주문 페이지를 반환합니다. */
		@Override
		public ConditionalOrderListResponse getConditionalOrders(
				long accountSeq,
				ConditionalOrderListStatus status,
				String symbol,
				String cursor,
			Integer limit) {
			requestedCursors.add(cursor);
			if (responses.isEmpty()) {
				callCount++;
				return new ConditionalOrderListResponse(
						ACCOUNT_SEQ, ConditionalOrderListStatus.OPEN, null,
						List.of(), null, false);
			}
			return responses.get(Math.min(callCount++, responses.size() - 1));
		}
	}
}
