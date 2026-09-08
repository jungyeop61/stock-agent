package com.jusika.backend.amountorderexecution;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.springframework.stereotype.Component;

import com.jusika.backend.order.AmountOrderSubmissionRequest;

/**
 * 계좌와 금액 주문 본문 전체로 향후 안전 복구에 사용할 SHA-256 지문을 계산합니다.
 */
@Component
public class AmountOrderRequestFingerprint {

	private static final String FORMAT_VERSION = "amount-order-v1";

	/**
	 * 최초 금액 주문 본문이 바뀌지 않았는지 비교할 결정적인 지문을 만듭니다.
	 *
	 * @param accountSeq 주문에 사용한 계좌 식별값
	 * @param request 최초 제출할 금액 주문 본문
	 * @return 64자리 소문자 16진수 SHA-256 지문
	 */
	public String calculate(long accountSeq, AmountOrderSubmissionRequest request) {
		if (request == null
				|| request.clientOrderId() == null
				|| request.clientOrderId().isBlank()
				|| request.symbol() == null
				|| request.symbol().isBlank()
				|| request.side() == null
				|| request.orderAmount() == null) {
			throw new IllegalArgumentException("금액 주문 요청의 필수값이 비어 있습니다.");
		}
		String canonical = String.join("\u0000",
				FORMAT_VERSION,
				Long.toString(accountSeq),
				request.clientOrderId(),
				request.symbol(),
				request.side().name(),
				request.orderAmount().toPlainString(),
				Boolean.toString(request.confirmHighValueOrder()));
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("금액 주문 요청 지문을 계산할 수 없습니다.", exception);
		}
	}
}
