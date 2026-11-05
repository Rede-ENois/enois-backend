package com.socialcoisa.rede.shared.web;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import jakarta.annotation.PostConstruct;

@Configuration
@Lazy(false) // the wildcard check below must run at boot even with spring.main.lazy-initialization=true
class CorsConfig {

	private static final String[] DEFAULT_ORIGINS = { "https://enois.ia.br" };

	@Value("${rede.cors.allowed-origins:}")
	private String allowedOrigins;

	/** Padrões confiáveis do app (aceita curinga, ex.: https://*.b7cloud.com.br). */
	@Value("${rede.origins.trusted:https://*.b7cloud.com.br}")
	private String trustedOrigins;

	/** Credentialed CORS with an "any origin" pattern would let every site call the API as the user. */
	@PostConstruct
	void rejectWildcardOrigins() {
		for (String origin : (allowedOrigins + "," + trustedOrigins).split(",")) {
			String trimmed = origin.trim();
			if (trimmed.equals("*") || trimmed.endsWith("://*")) {
				throw new IllegalStateException(
						"Wildcard CORS origin '" + trimmed + "' is not allowed; list the origins explicitly");
			}
		}
	}

	@Bean
	WebMvcConfigurer corsConfigurer() {
		return new WebMvcConfigurer() {
			@Override
			public void addCorsMappings(CorsRegistry registry) {
				// allowedOriginPatterns e obrigatorio para aceitar "*" junto com
				// allowCredentials(true); allowedOrigins("*") lanca excecao em toda requisicao.
				// Sem configuracao explicita, restringe as origens conhecidas do app.
				Set<String> patterns = new LinkedHashSet<>();
				if (allowedOrigins.isBlank()) {
					patterns.addAll(Arrays.asList(DEFAULT_ORIGINS));
				}
				else {
					patterns.addAll(Arrays.stream(allowedOrigins.split(",")).map(String::trim)
						.filter(s -> !s.isEmpty()).toList());
				}
				// O gate de origem também confia nesses padrões; o CORS precisa liberá-los junto,
				// senão o navegador do frontend leva "Invalid CORS request" mesmo com origem válida.
				patterns.addAll(Arrays.stream(trustedOrigins.split(",")).map(String::trim)
					.filter(s -> !s.isEmpty()).toList());
				// Credentials (session cookie) are never offered to a wildcard. Subdomain patterns
				// like https://*.b7cloud.com.br stay valid for the origin gate, but a credentialed
				// CORS grant needs an explicit origin: any sibling subdomain would otherwise be
				// allowed to read and write the API with the victim's cookie.
				patterns.removeIf(pattern -> pattern.contains("*"));
				if (patterns.isEmpty()) {
					patterns.addAll(Arrays.asList(DEFAULT_ORIGINS));
				}
				registry.addMapping("/**")
					.allowedOriginPatterns(patterns.toArray(String[]::new))
					.allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
					.allowedHeaders("*")
					.exposedHeaders("*")
					.allowCredentials(true);
			}
		};
	}
}
