package com.jusika.backend.sellablequantity;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jusika.backend.internalauth.InternalApiAuthority;
import com.jusika.backend.internalauth.RequiresInternalApiAuthority;
import com.jusika.backend.toss.orderinfo.TossSellableQuantityClient;

/**
 * 모바일 앱이나 개발자가 계좌의 종목별 매도 가능 수량을 조회할 HTTP 주소를 제공합니다.
 */
@RestController
@RequestMapping("/api/accounts")
@RequiresInternalApiAuthority(InternalApiAuthority.READ)
public class SellableQuantityController {

	private final TossSellableQuantityClient sellableQuantityClient;

	/**
	 * 실제 매도 가능 수량 조회를 담당하는 토스증권 클라이언트를 전달받습니다.
	 *
	 * @param sellableQuantityClient 토스증권 매도 가능 수량 조회 클라이언트
	 */
	public SellableQuantityController(TossSellableQuantityClient sellableQuantityClient) {
		this.sellableQuantityClient = sellableQuantityClient;
	}

	/**
	 * URL의 계좌 식별값과 종목 코드로 현재 매도 주문에 사용할 수 있는 수량을 조회합니다.
	 *
	 * @param accountSeq 계좌 목록 API에서 받은 계좌 식별값
	 * @param symbol 조회할 국내 또는 해외 주식 종목 코드
	 * @return 현재 새 매도 주문에 사용할 수 있는 수량
	 */
	@GetMapping("/{accountSeq}/stocks/{symbol}/sellable-quantity")
	public SellableQuantityResponse getSellableQuantity(
			@PathVariable long accountSeq,
			@PathVariable String symbol) {
		return sellableQuantityClient.getSellableQuantity(accountSeq, symbol);
	}
}
