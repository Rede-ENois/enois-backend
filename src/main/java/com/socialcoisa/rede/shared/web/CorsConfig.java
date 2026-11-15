package com.socialcoisa.rede.shared.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.socialcoisa.rede.apiv1.origins.AppOriginRegistry;

/**
 * CORS da API. A lista de origens vem do {@link AppOriginRegistry} — a MESMA que o portão de origem
 * usa. Antes eram duas leituras independentes (com tratamento diferente para curinga), e a mesma
 * origem podia ser aceita numa e recusada na outra.
 *
 * <p>{@code allowedOriginPatterns} reflete exatamente a origem pedida, mas as credenciais (cookie de
 * sessão) só vão para uma origem explícita do app — o registro já remove o curinga da lista
 * credenciável.</p>
 */
@Configuration
@Lazy(false) // a lista de origens precisa existir no boot, com a inicialização preguiçosa ligada
class CorsConfig {

	private final AppOriginRegistry origins;

	CorsConfig(AppOriginRegistry origins) {
		this.origins = origins;
	}

	@Bean
	WebMvcConfigurer corsConfigurer() {
		return new WebMvcConfigurer() {
			@Override
			public void addCorsMappings(CorsRegistry registry) {
				registry.addMapping("/**")
					.allowedOriginPatterns(origins.corsPatterns().toArray(String[]::new))
					.allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
					.allowedHeaders("*")
					.exposedHeaders("*")
					.allowCredentials(true);
			}
		};
	}
}
