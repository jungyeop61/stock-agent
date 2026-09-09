package com.jusika.backend.conditionalordermodification;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jusika.backend.internalauth.InternalApiAuthority;
import com.jusika.backend.internalauth.RequiresInternalApiAuthority;

/** 조건 주문 정정의 미리보기·승인·모의 실행·결과 조회 주소를 제공합니다. */
@RestController
@RequestMapping("/api/conditional-orders/modifications")
@RequiresInternalApiAuthority(InternalApiAuthority.ORDER)
public class ConditionalOrderModificationController {

	private final ConditionalOrderModificationService service;

	/** 조건 주문 정정 안전 흐름을 담당하는 서비스를 전달받습니다. */
	public ConditionalOrderModificationController(ConditionalOrderModificationService service) {
		this.service = service;
	}

	/** 실제 정정 없이 원주문과 새 전체 조건을 검증해 승인용 미리보기를 만듭니다. */
	@PostMapping("/preview")
	public ConditionalOrderModificationPreviewResponse createPreview(
			@RequestBody ConditionalOrderModificationPreviewRequest request) {
		return service.createPreview(request);
	}

	/** 유효한 승인 대기 조건 주문 정정 미리보기만 승인합니다. */
	@PostMapping("/previews/{previewId}/approve")
	public ConditionalOrderModificationPreviewResponse approvePreview(
			@PathVariable String previewId) {
		return service.approvePreview(previewId);
	}

	/** 승인된 미리보기를 최종 재검증한 뒤 현재 MOCK 모드에서 한 번 정정합니다. */
	@PostMapping("/previews/{previewId}/execute")
	public ConditionalOrderModificationExecutionResponse executePreview(
			@PathVariable String previewId) {
		return service.executeApprovedPreview(previewId);
	}

	/** 저장된 조건 주문 정정 실행 결과를 읽기 전용으로 조회합니다. */
	@GetMapping("/executions/{executionId}")
	@RequiresInternalApiAuthority(InternalApiAuthority.READ)
	public ConditionalOrderModificationExecutionResponse getExecution(
			@PathVariable String executionId) {
		return service.getExecution(executionId);
	}
}
