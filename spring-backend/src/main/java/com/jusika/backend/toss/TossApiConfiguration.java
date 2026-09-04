package com.jusika.backend.toss;

import java.net.http.HttpClient;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 토스증권 API 호출에 공통으로 사용할 HTTP 클라이언트를 설정합니다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TossApiProperties.class)
public class TossApiConfiguration {

	/**
	 * 토스증권 기본 주소와 연결 제한시간이 적용된 전용 REST 클라이언트를 만듭니다.
	 *
	 * @param properties 토스증권 API 연결 설정
	 * @return 토스증권 API 전용 REST 클라이언트
	 */
	@Bean
	RestClient tossRestClient(TossApiProperties properties) {
		HttpClient httpClient = HttpClient.newBuilder()
				.connectTimeout(properties.connectTimeout())
				.build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(properties.readTimeout());

		return RestClient.builder()
				.baseUrl(properties.baseUrl().toString())
				.requestFactory(requestFactory)
				.build();
	}
}
