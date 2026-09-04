package com.jusika.backend.buyingpower;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.jusika.backend.toss.orderinfo.TossBuyingPowerClient;

/**
 * 모바일 앱이나 개발자가 계좌의 통화별 매수 가능 금액을 조회할 HTTP 주소를 제공합니다.
 */
@RestController
@RequestMapping("/api/accounts")
public class BuyingPowerController {

	private final TossBuyingPowerClient buyingPowerClient;

	/**
	 * 실제 매수 가능 금액 조회를 담당하는 토스증권 클라이언트를 전달받습니다.
	 *
	 * @param buyingPowerClient 토스증권 매수 가능 금액 조회 클라이언트
	 */
	public BuyingPowerController(TossBuyingPowerClient buyingPowerClient) {
		this.buyingPowerClient = buyingPowerClient;
	}

	/**
	 * URL의 계좌 식별값과 쿼리의 통화 코드로 현금 매수 가능 금액을 조회합니다.
	 *
	 * @param accountSeq 계좌 목록 API에서 받은 계좌 식별값
	 * @param currency 조회할 통화 코드인 KRW 또는 USD
	 * @return 미수 없이 현금으로 매수할 수 있는 금액
	 */
	@GetMapping("/{accountSeq}/buying-power")
	public BuyingPowerResponse getBuyingPower(
			@PathVariable long accountSeq,
			@RequestParam String currency) {
		return buyingPowerClient.getBuyingPower(accountSeq, currency);
	}
}
