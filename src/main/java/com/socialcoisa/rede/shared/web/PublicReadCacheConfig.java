package com.socialcoisa.rede.shared.web;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.ShallowEtagHeaderFilter;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Cache HTTP das leituras publicas.
 *
 * <p>A API ja guarda o resultado no cache do processo ({@code CacheConfig}); o que falta aqui e o
 * navegador nao baixar de novo um corpo que acabou de receber. Duas pecas, e nada alem disso:</p>
 *
 * <ul>
 * <li>{@link ShallowEtagHeaderFilter} calcula um ETag do JSON. O navegador manda
 * {@code If-None-Match} e leva {@code 304} sem corpo quando nada mudou.</li>
 * <li>{@code Cache-Control: public, max-age=0, must-revalidate} — o corpo pode ficar guardado, mas
 * nunca e servido sem perguntar. E o {@code max-age=0} que impede a tela de mostrar dado velho
 * depois de uma acao do proprio usuario; a economia vem do 304, nao de servir sem conferir.</li>
 * </ul>
 *
 * <p><b>Nao vale para requisicao com sessao.</b> Resposta autenticada tem dado pessoal e ja sai com
 * {@code no-store} em {@link SecurityHeadersFilter}; aqui nem o ETag aparece, porque o filtro do
 * Spring ignora resposta marcada como {@code no-store}. Cache publico nunca guarda a visao de um
 * leitor logado.</p>
 *
 * <p>Fora da lista ficam streaming, upload, midia e qualquer rota de escrita: corpo grande, uma
 * resposta so, ou conteudo que nao pode ser comparado byte a byte.</p>
 */
@Configuration(proxyBeanMethods = false)
class PublicReadCacheConfig implements WebMvcConfigurer {

	/** Leituras cujo corpo e igual para todo mundo — e onde o 304 economiza trafego. */
	private static final String[] PUBLIC_READ_PATHS = {
		"/feed", "/feed/posts/*", "/users/*", "/users/by-id/*", "/ranking", "/profile/ranking",
		"/communities", "/communities/*", "/events", "/events/*", "/jobs", "/jobs/*",
		"/stats/community"
	};

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(new PublicReadCacheControlInterceptor()).addPathPatterns(PUBLIC_READ_PATHS);
	}

	@Bean
	FilterRegistrationBean<ShallowEtagHeaderFilter> publicReadEtagFilter() {
		ShallowEtagHeaderFilter filter = new ShallowEtagHeaderFilter();
		// ETag fraco: compara o corpo, nao promete nada sobre a origem.
		filter.setWriteWeakETag(true);
		FilterRegistrationBean<ShallowEtagHeaderFilter> registration = new FilterRegistrationBean<>(filter);
		registration.setName("publicReadEtagFilter");
		registration.addUrlPatterns(PUBLIC_READ_PATHS);
		// Antes do resto da pilha para envolver a resposta inteira.
		registration.setOrder(Ordered.LOWEST_PRECEDENCE - 10);
		return registration;
	}

	/**
	 * Politica de cache para leitura anonima. Escrita e requisicao autenticada passam batido: o
	 * {@code no-store} de {@link SecurityHeadersFilter} continua sendo o que vale para elas.
	 */
	static class PublicReadCacheControlInterceptor implements
			org.springframework.web.servlet.HandlerInterceptor {

		@Override
		public boolean preHandle(jakarta.servlet.http.HttpServletRequest request,
				jakarta.servlet.http.HttpServletResponse response, Object handler) {
			// Quem ja decidiu a politica (resposta autenticada sai com no-store) manda. Sobrescrever
			// aqui publicaria a visao pessoal por cima do no-store — nunca trocar um pelo outro.
			if (response.getHeader("Cache-Control") != null) {
				return true;
			}
			if (request.getHeader("X-Session-Token") != null) {
				return true;
			}
			if ("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod())) {
				response.setHeader("Cache-Control", "public, max-age=0, must-revalidate");
			}
			return true;
		}
	}
}
