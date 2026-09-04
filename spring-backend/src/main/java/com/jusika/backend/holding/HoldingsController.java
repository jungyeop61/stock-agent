package com.jusika.backend.holding;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jusika.backend.toss.asset.TossHoldingsClient;

/**
 * 모바일 앱이나 개발자가 계좌의 보유주식과 평가손익을 조회할 HTTP 주소를 제공합니다.
 */
@RestController
@RequestMapping("/api/accounts")
public class HoldingsController {

	private final TossHoldingsClient holdingsClient;

	/**
	 * 실제 보유주식 조회를 담당하는 토스증권 클라이언트를 전달받습니다.
	 *
	 * @param holdingsClient 토스증권 보유주식 조회 클라이언트
	 */
	public HoldingsController(TossHoldingsClient holdingsClient) {
		this.holdingsClient = holdingsClient;
	}

	/**
	 * URL에 포함된 계좌 식별값으로 전체 보유주식과 평가손익을 조회합니다.
	 *
	 * @param accountSeq 계좌 목록 API에서 받은 계좌 식별값
	 * @return 계좌의 보유주식과 평가 요약
	 */
	@GetMapping("/{accountSeq}/holdings")
	public HoldingsResponse getHoldings(@PathVariable long accountSeq) {
		return holdingsClient.getHoldings(accountSeq);
	}
}
