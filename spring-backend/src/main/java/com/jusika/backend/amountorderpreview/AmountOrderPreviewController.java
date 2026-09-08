package com.jusika.backend.amountorderpreview;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 미국 주식 달러 금액 매수 미리보기를 생성하고 조회할 HTTP 주소를 제공합니다.
 */
@RestController
@RequestMapping("/api/orders/amount")
public class AmountOrderPreviewController {

	private final AmountOrderPreviewService previewService;

	/**
	 * 금액 주문 검증과 저장을 담당하는 서비스를 전달받습니다.
	 *
	 * @param previewService 금액 주문 미리보기 서비스
	 */
	public AmountOrderPreviewController(AmountOrderPreviewService previewService) {
		this.previewService = previewService;
	}

	/**
	 * 요청 내용을 검증하고 실제 주문 없이 계산한 금액 주문 미리보기를 반환합니다.
	 *
	 * @param request 계좌, 미국 종목과 달러 주문 금액
	 * @return 저장된 금액 주문 미리보기
	 */
	@PostMapping("/preview")
	public AmountOrderPreviewResponse createPreview(@RequestBody AmountOrderPreviewRequest request) {
		return previewService.createPreview(request);
	}

	/**
	 * 식별값에 해당하는 저장된 금액 주문 미리보기를 반환합니다.
	 *
	 * @param previewId 조회할 금액 주문 미리보기 식별값
	 * @return 저장 당시의 변경 불가 계산 결과
	 */
	@GetMapping("/previews/{previewId}")
	public AmountOrderPreviewResponse getPreview(@PathVariable String previewId) {
		return previewService.getPreview(previewId);
	}
}
