package com.jusika.backend.stock;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jusika.backend.toss.market.TossPriceClient;

/**
 * 모바일 앱이나 개발자가 종목 현재가를 조회할 수 있는 HTTP 주소를 제공합니다.
 */
@RestController
@RequestMapping("/api/stocks")
public class StockPriceController {

	private final TossPriceClient priceClient;

	/**
	 * 실제 현재가 조회를 담당하는 토스증권 클라이언트를 전달받습니다.
	 *
	 * @param priceClient 토스증권 현재가 조회 클라이언트
	 */
	public StockPriceController(TossPriceClient priceClient) {
		this.priceClient = priceClient;
	}

	/**
	 * URL에 포함된 종목 코드의 현재가를 조회해 JSON으로 반환합니다.
	 *
	 * @param symbol 조회할 종목 코드
	 * @return 토스증권에서 받은 최신 현재가
	 */
	@GetMapping("/{symbol}/price")
	public StockPriceResponse getCurrentPrice(@PathVariable String symbol) {
		return priceClient.getCurrentPrice(symbol);
	}
}
