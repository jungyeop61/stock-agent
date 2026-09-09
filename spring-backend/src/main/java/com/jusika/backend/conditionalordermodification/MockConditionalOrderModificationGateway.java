package com.jusika.backend.conditionalordermodification;

import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.jusika.backend.conditionalorder.ConditionalOrderModificationResponse;
import com.jusika.backend.conditionalorder.ConditionalOrderModificationSubmissionRequest;

/** 실제 계좌를 변경하지 않고 새 조건 주문 식별값만 만드는 정정 경계입니다. */
@Component
@ConditionalOnProperty(name = "jusika.broker.mode", havingValue = "mock", matchIfMissing = true)
class MockConditionalOrderModificationGateway implements ConditionalOrderModificationGateway {

	/** 실제 토스증권 요청 없이 대체 조건 주문 식별값을 반환합니다. */
	@Override
	public ConditionalOrderModificationResponse modify(
			long accountSeq,
			String originalConditionalOrderId,
			ConditionalOrderModificationSubmissionRequest request) {
		return new ConditionalOrderModificationResponse("mock-modified-" + UUID.randomUUID());
	}

	/** 현재 실행이 모의 정정임을 반환합니다. */
	@Override
	public String mode() {
		return "MOCK";
	}
}
