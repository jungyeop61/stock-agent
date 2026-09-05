package com.jusika.backend.toss.orderhistory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.jusika.backend.account.AccountResponse;
import com.jusika.backend.orderhistory.OrderListResponse;
import com.jusika.backend.orderhistory.OrderListStatus;
import com.jusika.backend.toss.account.TossAccountClient;

/**
 * 사용자가 명시적으로 허용했을 때만 실제 토스증권 서버에서 주문 목록을 읽기 전용으로 확인합니다.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "RUN_TOSS_LIVE_TEST", matches = "true")
class TossOrderListLiveTests {

	@Autowired
	private TossAccountClient accountClient;

	@Autowired
	private TossOrderHistoryClient orderHistoryClient;

	/**
	 * 실제 계좌의 진행 중·종료 주문을 조회하고 주문 식별값과 금융값을 출력하지 않은 채 형식만 검사합니다.
	 */
	@Test
	@DisplayName("실제 토스증권 계좌에서 진행 중 주문과 종료 주문을 안전하게 조회한다")
	void 실제_토스증권_계좌에서_진행_중_주문과_종료_주문을_안전하게_조회한다() {
		List<AccountResponse> accounts = accountClient.getAccounts();
		assertThat(accounts).isNotEmpty();

		long accountSeq = accounts.getFirst().accountSeq();
		OrderListResponse openOrders = orderHistoryClient.getOrders(
				accountSeq, OrderListStatus.OPEN, null, null, null, null, null);
		OrderListResponse closedOrders = orderHistoryClient.getOrders(
				accountSeq, OrderListStatus.CLOSED, null, null, null, null, null);

		assertOrderList(openOrders, accountSeq, OrderListStatus.OPEN);
		assertOrderList(closedOrders, accountSeq, OrderListStatus.CLOSED);
	}

	/**
	 * 실제 목록이 요청 계좌·목록 상태와 일치하고 변환된 주문에 빈 항목이 없는지 검사합니다.
	 *
	 * @param response 검사할 실제 주문 목록 응답
	 * @param accountSeq 요청에 사용한 계좌 식별값
	 * @param status 요청에 사용한 주문 목록 상태
	 */
	private void assertOrderList(
			OrderListResponse response,
			long accountSeq,
			OrderListStatus status) {
		assertThat(response.accountSeq()).isEqualTo(accountSeq);
		assertThat(response.listStatus()).isEqualTo(status);
		assertThat(response.orders()).doesNotContainNull();
		assertThat(response.orders()).allSatisfy(order ->
				assertThat(order.accountSeq()).isEqualTo(accountSeq));
		if (status == OrderListStatus.OPEN) {
			assertThat(response.hasNext()).isFalse();
			assertThat(response.nextCursor()).isNull();
		}
	}
}
