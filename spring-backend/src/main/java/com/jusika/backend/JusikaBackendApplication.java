package com.jusika.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.jusika.backend.brokersafety.BrokerSafetyProperties;
import com.jusika.backend.internalauth.InternalApiKeyProperties;
import com.jusika.backend.orderpreview.OrderPreviewProperties;

/**
 * 주식아 스프링 백엔드를 시작하는 진입점입니다.
 */
@SpringBootApplication
@EnableConfigurationProperties({
		OrderPreviewProperties.class,
		BrokerSafetyProperties.class,
		InternalApiKeyProperties.class
})
public class JusikaBackendApplication {

	/**
	 * 스프링 애플리케이션을 실행하고 필요한 설정과 객체를 준비합니다.
	 *
	 * @param args 프로그램을 실행할 때 전달받은 명령줄 인자
	 */
	public static void main(String[] args) {
		SpringApplication.run(JusikaBackendApplication.class, args);
	}
}
