package com.socialcoisa.rede.shared.web;

import java.util.Arrays;

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
				// allowedOriginPatterns e obrigatorio para aceitar "*" junto com
				// allowCredentials(true); allowedOrigins("*") lanca excecao em toda requisicao.
				String[] patterns = allowedOrigins.isBlank()
					? new String[] { "*" }
					: Arrays.stream(allowedOrigins.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new);
				registry.addMapping("/api/**")
					.allowedOriginPatterns(patterns)
					.allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
					.allowedHeaders("*")
					.exposedHeaders("*")
					.allowCredentials(true);
			}
		};
	}
}
