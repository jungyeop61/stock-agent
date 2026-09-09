package com.jusika.backend.account;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jusika.backend.internalauth.InternalApiAuthority;
import com.jusika.backend.internalauth.RequiresInternalApiAuthority;
import com.jusika.backend.toss.account.TossAccountClient;

/**
 * 모바일 앱이나 개발자가 사용 가능한 증권 계좌를 조회할 HTTP 주소를 제공합니다.
 */
@RestController
@RequestMapping("/api/accounts")
@RequiresInternalApiAuthority(InternalApiAuthority.READ)
public class AccountController {

	private final TossAccountClient accountClient;

	/**
	 * 실제 계좌 목록 조회를 담당하는 토스증권 클라이언트를 전달받습니다.
	 *
	 * @param accountClient 토스증권 계좌 목록 조회 클라이언트
	 */
	public AccountController(TossAccountClient accountClient) {
		this.accountClient = accountClient;
	}

	/**
	 * 사용할 수 있는 계좌 목록을 조회해 JSON 배열로 반환합니다.
	 *
	 * @return 실제 계좌번호가 가려진 계좌 목록
	 */
	@GetMapping
	public List<AccountResponse> getAccounts() {
		return accountClient.getAccounts();
	}
}
