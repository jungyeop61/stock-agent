package com.jusika.backend.brokeraudit;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.jusika.backend.internalauth.InternalApiAuthority;
import com.jusika.backend.internalauth.RequiresInternalApiAuthority;

/** 읽기 권한으로 LIVE 주문 변경 감사 사건을 조회할 HTTP 주소를 제공합니다. */
@RestController
@RequestMapping("/api/broker/mutation-audits")
@RequiresInternalApiAuthority(InternalApiAuthority.READ)
public class BrokerMutationAuditController {

	private final BrokerMutationAuditService auditService;

	/** LIVE 주문 변경 감사 조회 서비스를 연결합니다. */
	public BrokerMutationAuditController(BrokerMutationAuditService auditService) {
		this.auditService = auditService;
	}

	/**
	 * 최신 LIVE 주문 변경 감사 사건을 커서 기반으로 반환합니다.
	 *
	 * @param beforeEventId 이전 응답에서 받은 선택 커서
	 * @param limit 한 번에 반환할 사건 수
	 * @return 민감정보 없는 감사 사건 페이지
	 */
	@GetMapping
	public BrokerMutationAuditListResponse getEvents(
			@RequestParam(required = false) Long beforeEventId,
			@RequestParam(defaultValue = "50") int limit) {
		return auditService.getEvents(beforeEventId, limit);
	}
}
