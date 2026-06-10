package com.socialcoisa.rede.shared.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
class CorsConfig {

	@Value("${rede.cors.allowed-origins:}")
	private String allowedOrigins;

	@Bean
	WebMvcConfigurer corsConfigurer() {
		return new WebMvcConfigurer() {
			@Override
			public void addCorsMappings(CorsRegistry registry) {
				String[] origins = allowedOrigins.isBlank() ? new String[]{"http://localhost:5173"} : allowedOrigins.split(",");
				registry.addMapping("/api/**")
					.allowedOrigins(origins)
					.allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
					.allowedHeaders("*")
					.allowCredentials(true);
			}
		};
	}
}
