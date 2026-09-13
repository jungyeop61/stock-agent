package com.jusika.backend.brokersafety;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.jusika.backend.conditionalorder.ConditionalOrderDetailResponse;
import com.jusika.backend.conditionalorder.ConditionalOrderListResponse;
import com.jusika.backend.conditionalorder.ConditionalOrderListStatus;
import com.jusika.backend.orderhistory.OrderDetailResponse;
import com.jusika.backend.orderhistory.OrderListResponse;
import com.jusika.backend.orderhistory.OrderListStatus;
import com.jusika.backend.toss.conditionalorder.TossConditionalOrderClient;
import com.jusika.backend.toss.orderhistory.TossOrderHistoryClient;

/**
 * 토스증권의 읽기 전용 목록을 합산해 LIVE 신규 주문과 정정의 활성 주문 여유를 검사합니다.
 */
@Service
public class BrokerLiveOpenOrderCapacityService {

	private static final int CONDITIONAL_PAGE_LIMIT = 100;
	private static final int MAX_CONDITIONAL_PAGES = 100;

	private final BrokerLiveOpenOrderLimitProperties limits;
	private final TossOrderHistoryClient orderHistoryClient;
	private final TossConditionalOrderClient conditionalOrderClient;

	/** 설정한 상한과 두 읽기 전용 토스 주문 목록 클라이언트를 전달받습니다. */
	public BrokerLiveOpenOrderCapacityService(
			BrokerSafetyProperties properties,
			TossOrderHistoryClient orderHistoryClient,
			TossConditionalOrderClient conditionalOrderClient) {
		this.limits = properties.liveOpenOrderLimits();
		this.orderHistoryClient = orderHistoryClient;
		this.conditionalOrderClient = conditionalOrderClient;
	}

	/**
	 * 현재 일반·조건 활성 주문 수에 요청의 예상 증가량을 더해 두 상한 이내인지 확인합니다.
	 * 목록 조회에 실패하거나 페이지 응답이 모순되면 실제 변경 요청을 보내지 않도록 차단합니다.
	 */
	public void requireCapacity(
			long accountSeq,
			String symbol,
			BrokerOpenOrderCapacityOperation operation) {
		validateRequest(accountSeq, symbol, operation);
		if (!limits.isConfigured()) {
			throw new BrokerMutationBlockedException("LIVE 활성 주문 개수 한도가 설정되어 있지 않습니다.");
		}
		String normalizedSymbol = symbol.toUpperCase(Locale.ROOT);
		try {
			OrderCounts counts = countNormalOrders(accountSeq, normalizedSymbol);
			ensureWithinLimits(counts, operation.projectedIncrease());
			counts = countConditionalOrders(
					accountSeq, normalizedSymbol, counts, operation.projectedIncrease());
			ensureWithinLimits(counts, operation.projectedIncrease());
		} catch (BrokerMutationBlockedException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			throw new BrokerMutationBlockedException(
					"활성 주문 수를 확인할 수 없어 실제 주문을 차단했습니다.");
		}
	}

	/** 계좌, 종목과 검사 유형이 안전한 입력인지 외부 조회 전에 확인합니다. */
	private void validateRequest(
			long accountSeq,
			String symbol,
			BrokerOpenOrderCapacityOperation operation) {
		if (accountSeq <= 0 || symbol == null || symbol.isBlank() || operation == null) {
			throw new IllegalArgumentException("LIVE 활성 주문 개수 검사값이 올바르지 않습니다.");
		}
	}

	/** 진행 중 일반 주문을 한 번 조회해 계좌 전체와 동일 종목 개수를 계산합니다. */
	private OrderCounts countNormalOrders(long accountSeq, String symbol) {
		OrderListResponse response = orderHistoryClient.getOrders(
				accountSeq, OrderListStatus.OPEN, null, null, null, null, null);
		if (response == null
				|| response.accountSeq() != accountSeq
				|| response.listStatus() != OrderListStatus.OPEN
				|| response.orders() == null
				|| response.hasNext()
				|| response.nextCursor() != null) {
			throw new IllegalStateException("진행 중 일반 주문 목록 응답이 올바르지 않습니다.");
		}
		long instrumentCount = 0;
		for (OrderDetailResponse order : response.orders()) {
			validateNormalOrder(order, accountSeq);
			if (symbol.equalsIgnoreCase(order.symbol())) {
				instrumentCount++;
			}
		}
		return new OrderCounts(response.orders().size(), instrumentCount);
	}

	/** 조건 주문 목록의 모든 페이지를 순회하며 기존 일반 주문 개수에 합산합니다. */
	private OrderCounts countConditionalOrders(
			long accountSeq,
			String symbol,
			OrderCounts initialCounts,
			int projectedIncrease) {
		long accountCount = initialCounts.accountCount();
		long instrumentCount = initialCounts.instrumentCount();
		String cursor = null;
		Set<String> seenCursors = new HashSet<>();
		for (int page = 0; page < MAX_CONDITIONAL_PAGES; page++) {
			ConditionalOrderListResponse response = conditionalOrderClient.getConditionalOrders(
					accountSeq, ConditionalOrderListStatus.OPEN, null, cursor,
					CONDITIONAL_PAGE_LIMIT);
			validateConditionalPage(response, accountSeq);
			for (ConditionalOrderDetailResponse order : response.conditionalOrders()) {
				validateConditionalOrder(order, accountSeq);
				accountCount++;
				if (symbol.equalsIgnoreCase(order.symbol())) {
					instrumentCount++;
				}
			}
			OrderCounts counts = new OrderCounts(accountCount, instrumentCount);
			ensureWithinLimits(counts, projectedIncrease);
			if (!response.hasNext()) {
				return counts;
			}
			String nextCursor = response.nextCursor();
			if (nextCursor == null || nextCursor.isBlank() || !seenCursors.add(nextCursor)) {
				throw new IllegalStateException("조건 주문 페이지 커서가 올바르지 않습니다.");
			}
			cursor = nextCursor;
		}
		throw new IllegalStateException("조건 주문 페이지 수가 안전 조회 범위를 초과했습니다.");
	}

	/** 일반 주문 항목이 요청 계좌에 속하고 종목 코드가 존재하는지 확인합니다. */
	private void validateNormalOrder(OrderDetailResponse order, long accountSeq) {
		if (order == null || order.accountSeq() != accountSeq
				|| order.symbol() == null || order.symbol().isBlank()) {
			throw new IllegalStateException("진행 중 일반 주문 항목이 올바르지 않습니다.");
		}
	}

	/** 조건 주문 페이지가 요청 계좌의 진행 중 전체 목록인지 확인합니다. */
	private void validateConditionalPage(
			ConditionalOrderListResponse response,
			long accountSeq) {
		if (response == null
				|| response.accountSeq() != accountSeq
				|| response.listStatus() != ConditionalOrderListStatus.OPEN
				|| response.symbol() != null
				|| response.conditionalOrders() == null
				|| (response.hasNext() && response.nextCursor() == null)
				|| (!response.hasNext() && response.nextCursor() != null)) {
			throw new IllegalStateException("진행 중 조건 주문 목록 응답이 올바르지 않습니다.");
		}
	}

	/** 조건 주문 항목이 요청 계좌에 속하고 종목 코드가 존재하는지 확인합니다. */
	private void validateConditionalOrder(ConditionalOrderDetailResponse order, long accountSeq) {
		if (order == null || order.accountSeq() != accountSeq
				|| order.symbol() == null || order.symbol().isBlank()) {
			throw new IllegalStateException("진행 중 조건 주문 항목이 올바르지 않습니다.");
		}
	}

	/** 예상 주문 수가 계좌 전체 또는 동일 종목 상한을 넘으면 실제 주문을 차단합니다. */
	private void ensureWithinLimits(OrderCounts counts, int projectedIncrease) {
		if (counts.accountCount() + projectedIncrease > limits.maxOpenOrdersPerAccount()) {
			throw new BrokerMutationBlockedException("계좌의 활성 주문 개수가 안전 한도를 초과합니다.");
		}
		if (counts.instrumentCount() + projectedIncrease > limits.maxOpenOrdersPerInstrument()) {
			throw new BrokerMutationBlockedException("동일 종목의 활성 주문 개수가 안전 한도를 초과합니다.");
		}
	}

	/** 계좌 전체와 동일 종목의 현재 활성 주문 개수를 함께 보관합니다. */
	private record OrderCounts(long accountCount, long instrumentCount) {
	}
}
