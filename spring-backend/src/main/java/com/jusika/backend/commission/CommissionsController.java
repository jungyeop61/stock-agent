package com.jusika.backend.commission;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jusika.backend.internalauth.InternalApiAuthority;
import com.jusika.backend.internalauth.RequiresInternalApiAuthority;
import com.jusika.backend.toss.orderinfo.TossCommissionsClient;

/**
 * 모바일 앱이나 개발자가 계좌의 시장별 매매 수수료를 조회할 HTTP 주소를 제공합니다.
 */
@RestController
@RequestMapping("/api/accounts")
@RequiresInternalApiAuthority(InternalApiAuthority.READ)
public class CommissionsController {

	private final TossCommissionsClient commissionsClient;

	/**
	 * 실제 수수료 조회를 담당하는 토스증권 클라이언트를 전달받습니다.
	 *
	 * @param commissionsClient 토스증권 매매 수수료 조회 클라이언트
	 */
	public CommissionsController(TossCommissionsClient commissionsClient) {
		this.commissionsClient = commissionsClient;
	}

	/**
	 * URL의 계좌 식별값으로 국내와 미국 시장의 매매 수수료를 조회합니다.
	 *
	 * @param accountSeq 계좌 목록 API에서 받은 계좌 식별값
	 * @return 시장별 수수료율과 적용 기간 목록
	 */
	@GetMapping("/{accountSeq}/commissions")
	public CommissionsResponse getCommissions(@PathVariable long accountSeq) {
		return commissionsClient.getCommissions(accountSeq);
	}
}
