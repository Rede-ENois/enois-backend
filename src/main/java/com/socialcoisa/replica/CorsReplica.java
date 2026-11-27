package com.socialcoisa.replica;

import java.net.URI;
import java.util.Arrays;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

final class CorsReplica implements WebMvcConfigurer {

	private final String[] origens;

	CorsReplica(@Value("${rede.replica.cors-origins:}") String origens) {
		this.origens = Arrays.stream(origens.split(",")).map(String::trim).filter(s -> !s.isEmpty())
			.map(CorsReplica::verificarOrigem).distinct().toArray(String[]::new);
	}

	private static String verificarOrigem(String origem) {
		var uri = URI.create(origem);
		if (!"https".equals(uri.getScheme()) || uri.getHost() == null || !uri.getRawPath().isEmpty() ||
				uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
			throw new IllegalArgumentException("A réplica exige origens HTTPS explícitas.");
		}
		return origem;
	}

	@Override
	public void addCorsMappings(CorsRegistry registry) {
		for (String rota : new String[] {"/feed/posts", "/auth/login", "/auth/refresh", "/auth/logout", "/auth/2fa/verify",
				"/auth/signup", "/auth/email/verify", "/auth/email/resend"}) {
			registry.addMapping(rota).allowedOrigins(origens)
				.allowedMethods("POST", "OPTIONS")
				.allowedHeaders("Content-Type", "Idempotency-Key", "X-CSRF-Protection",
					"X-Session-Token", "X-Client-Origin", "X-Device-Fingerprint")
				.allowCredentials(true);
		}
		for (String rota : new String[] {"/feed/posts/*", "/auth/me", "/auth/config", "/auth/signup-challenge"}) {
			registry.addMapping(rota).allowedOrigins(origens)
				.allowedMethods("GET", "HEAD", "OPTIONS")
				.allowedHeaders("Accept", "Content-Type", "X-Session-Token", "X-Client-Origin", "X-Device-Fingerprint")
				.allowCredentials(true);
		}
	}
}
