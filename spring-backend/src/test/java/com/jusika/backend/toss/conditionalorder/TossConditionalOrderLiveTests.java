package com.jusika.backend.toss.conditionalorder;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.jusika.backend.account.AccountResponse;
import com.jusika.backend.conditionalorder.ConditionalOrderListResponse;
import com.jusika.backend.conditionalorder.ConditionalOrderListStatus;
import com.jusika.backend.toss.account.TossAccountClient;

/**
 * 사용자가 명시적으로 허용했을 때만 실제 토스증권 서버에서 조건 주문을 읽기 전용으로 확인합니다.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "RUN_TOSS_LIVE_TEST", matches = "true")
class TossConditionalOrderLiveTests {

	@Autowired
	private TossAccountClient accountClient;

	@Autowired
	private TossConditionalOrderClient conditionalOrderClient;

	/**
	 * 실제 첫 계좌의 진행 중·종료 조건 주문을 읽고 식별값과 금융값을 출력하지 않은 채 형식만 검사합니다.
	 */
	@Test
	@DisplayName("실제 토스증권 계좌에서 조건 주문 목록을 안전하게 조회한다")
	void 실제_토스증권_계좌에서_조건_주문_목록을_안전하게_조회한다() {
		List<AccountResponse> accounts = accountClient.getAccounts();
		assertThat(accounts).isNotEmpty();

		long accountSeq = accounts.getFirst().accountSeq();
		ConditionalOrderListResponse openOrders = conditionalOrderClient.getConditionalOrders(
				accountSeq, ConditionalOrderListStatus.OPEN, null, null, null);
		ConditionalOrderListResponse closedOrders = conditionalOrderClient.getConditionalOrders(
				accountSeq, ConditionalOrderListStatus.CLOSED, null, null, null);

		조건_주문_목록_형식을_검사한다(openOrders, accountSeq, ConditionalOrderListStatus.OPEN);
		조건_주문_목록_형식을_검사한다(closedOrders, accountSeq, ConditionalOrderListStatus.CLOSED);
	}

	/**
	 * 실제 목록이 요청 계좌·목록 상태와 일치하고 빈 항목을 포함하지 않는지 검사합니다.
	 *
	 * @param response 검사할 실제 조건 주문 목록 응답
	 * @param accountSeq 요청에 사용한 계좌 식별값
	 * @param status 요청에 사용한 조건 주문 목록 상태
	 */
	private void 조건_주문_목록_형식을_검사한다(
			ConditionalOrderListResponse response,
			long accountSeq,
			ConditionalOrderListStatus status) {
		assertThat(response.accountSeq()).isEqualTo(accountSeq);
		assertThat(response.listStatus()).isEqualTo(status);
		assertThat(response.conditionalOrders()).doesNotContainNull();
		assertThat(response.conditionalOrders()).allSatisfy(order ->
				assertThat(order.accountSeq()).isEqualTo(accountSeq));
	}
}
