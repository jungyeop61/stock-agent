package com.jusika.backend.toss.auth;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Component;

/**
 * 발급받은 토스증권 액세스 토큰을 만료 전까지 메모리에 보관하고 재사용합니다.
 */
@Component
public class TossAccessTokenProvider {

	private static final long EXPIRATION_SAFETY_SECONDS = 60;

	private final TossAuthClient authClient;
	private final Clock clock;

	private String cachedAccessToken;
	private Instant reusableUntil = Instant.EPOCH;

	/**
	 * 토큰 발급 클라이언트와 현재 시각을 제공할 시계를 전달받습니다.
	 *
	 * @param authClient 실제 액세스 토큰을 발급하는 인증 클라이언트
	 * @param clock 토큰 만료 시각을 계산할 때 사용할 시계
	 */
	public TossAccessTokenProvider(TossAuthClient authClient, Clock clock) {
		this.authClient = authClient;
		this.clock = clock;
	}

	/**
	 * 아직 유효한 토큰은 재사용하고, 없거나 만료가 가까우면 새 토큰을 한 번 발급합니다.
	 * 동시에 여러 요청이 들어와도 중복 발급되지 않도록 한 번에 하나씩 처리합니다.
	 *
	 * @return 토스증권 API의 Authorization 헤더에 사용할 액세스 토큰
	 */
	public synchronized String getAccessToken() {
		Instant now = clock.instant();
		if (cachedAccessToken != null && now.isBefore(reusableUntil)) {
			return cachedAccessToken;
		}

		TossTokenResponse response = authClient.issueAccessToken();
		cachedAccessToken = response.accessToken();
		long reusableSeconds = Math.max(0, response.expiresIn() - EXPIRATION_SAFETY_SECONDS);
		reusableUntil = now.plusSeconds(reusableSeconds);
		return cachedAccessToken;
	}
}
