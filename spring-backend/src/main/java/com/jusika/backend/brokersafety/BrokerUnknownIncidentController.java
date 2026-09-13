package com.jusika.backend.brokersafety;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jusika.backend.internalauth.InternalApiAuthority;
import com.jusika.backend.internalauth.RequiresInternalApiAuthority;

/** 결과 불명 자동 안전정지 상태 조회와 명시적 사용자 확인 HTTP 주소를 제공합니다. */
@RestController
@RequestMapping("/api/broker/unknown-incidents")
@RequiresInternalApiAuthority(InternalApiAuthority.READ)
public class BrokerUnknownIncidentController {

	private final BrokerUnknownIncidentAcknowledgementService acknowledgementService;

	/** 결과 불명 사고 확인 상태 서비스를 연결합니다. */
	public BrokerUnknownIncidentController(
			BrokerUnknownIncidentAcknowledgementService acknowledgementService) {
		this.acknowledgementService = acknowledgementService;
	}

	/** 민감한 주문 정보 없이 현재 자동 안전정지와 마지막 확인 상태를 조회합니다. */
	@GetMapping
	public BrokerUnknownIncidentStatusResponse getStatus() {
		return acknowledgementService.getStatus();
	}

	/** 주문 권한과 명시적 확인을 거쳐 지정 UNKNOWN 사건까지 확인 처리합니다. */
	@PostMapping("/acknowledgements")
	@RequiresInternalApiAuthority(InternalApiAuthority.ORDER)
	public BrokerUnknownIncidentStatusResponse acknowledge(
			@RequestBody BrokerUnknownIncidentAcknowledgementRequest request) {
		return acknowledgementService.acknowledge(request);
	}
}
