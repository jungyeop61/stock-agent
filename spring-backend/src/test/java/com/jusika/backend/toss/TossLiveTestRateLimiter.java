package com.jusika.backend.toss;

import java.time.Duration;

/**
 * 실제 토스증권 라이브 테스트가 문서화된 ACCOUNT 초당 1회 제한을 넘지 않도록 직렬화합니다.
 */
public final class TossLiveTestRateLimiter {

	private static final long ACCOUNT_INTERVAL_NANOS = Duration.ofMillis(1100).toNanos();

	private static long lastAccountRequestStartedAt;

	private TossLiveTestRateLimiter() {
	}

	/**
	 * 같은 JVM에서 다음 계좌 목록 요청을 보내기 전에 최소 간격을 확보합니다.
	 */
	public static synchronized void awaitAccountSlot() {
		long now = System.nanoTime();
		long remainingNanos = ACCOUNT_INTERVAL_NANOS - (now - lastAccountRequestStartedAt);
		if (lastAccountRequestStartedAt != 0 && remainingNanos > 0) {
			try {
				long millis = Duration.ofNanos(remainingNanos).toMillis();
				int nanos = (int) (remainingNanos - Duration.ofMillis(millis).toNanos());
				Thread.sleep(millis, nanos);
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("토스증권 라이브 테스트 대기가 중단되었습니다.", exception);
			}
		}
		lastAccountRequestStartedAt = System.nanoTime();
	}
}
