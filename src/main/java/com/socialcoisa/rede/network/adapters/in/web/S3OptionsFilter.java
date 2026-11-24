package com.socialcoisa.rede.network.adapters.in.web;

import java.io.IOException;
import java.util.List;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * O {@code OPTIONS} e os verbos que o gateway S3 não tem, no vocabulário do S3.
 *
 * <p><b>Antes:</b> o {@code OPTIONS} nunca chegava ao gateway. O preflight de CORS do Control
 * Plane respondia todo {@code OPTIONS} antes de qualquer autenticação, e o {@code Allow} era a
 * lista do servidor — uma credencial só de leitura ouvia que podia apagar. Quando aquele filtro não
 * respondia, o próprio {@link org.springframework.web.servlet.DispatcherServlet} montava o
 * {@code Allow} com os métodos <b>mapeados</b> (inclusive {@code TRACE} e {@code PATCH}, que nenhuma
 * rota atende). Em nenhum dos dois a resposta era o que a credencial pode fazer. O verbo não
 * mapeado saía como JSON do Control Plane ({@code {"code":"METHOD_NOT_ALLOWED"}}), que o SDK não lê.</p>
 *
 * <p><b>Agora:</b> em {@code /s3/**}, o {@code OPTIONS} sem {@code Origin} e o verbo desconhecido
 * respondem aqui, depois da assinatura: {@code OPTIONS} com assinatura é {@code 200} com o
 * {@code Allow} das operações da credencial; sem assinatura é {@code 403} em XML; verbo desconhecido
 * é {@code 405} em XML com o {@code Allow} da rota (§4.62). O preflight de navegador
 * ({@code Origin} + {@code Access-Control-Request-Method}) continua do CORS — o navegador pergunta
 * antes de ter a credencial, e ali o {@code OPTIONS} não executa nada.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 6)
class S3OptionsFilter extends OncePerRequestFilter {

	/** O que alguma rota do gateway atende — o {@code Allow} da rota, não o do servidor inteiro. */
	private static final String ALLOW_DA_ROTA = "GET, HEAD, PUT, DELETE, POST, OPTIONS";

	private final S3GatewayController gateway;

	private final UrlPathHelper paths = new UrlPathHelper();

	S3OptionsFilter(S3GatewayController gateway) {
		this.gateway = gateway;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String caminho = paths.getPathWithinApplication(request);
		// `/s3` ou `/s3/...`, nunca `/s3alguma-coisa` (prefixo de outra rota).
		if (!(caminho.equals("/s3") || caminho.startsWith("/s3/"))) {
			chain.doFilter(request, response);
			return;
		}
		if (request.getHeader("Origin") != null && request.getHeader("Access-Control-Request-Method") != null) {
			// O navegador pergunta antes de mandar credencial: quem responde é o CORS.
			chain.doFilter(request, response);
			return;
		}
		String metodo = request.getMethod();
		if (HttpMethod.OPTIONS.matches(metodo)) {
			try {
				gateway.responderOptions(request, response);
			}
			catch (S3ApiException recusado) {
				// Fora do controller, o `@ControllerAdvice` não alcança: o XML do S3 sai daqui.
				response.setStatus(recusado.status());
				response.setContentType("application/xml; charset=utf-8");
				response.getWriter().write(recusado.toXml(request.getRequestURI(), null));
			}
			return;
		}
		boolean conhecido = HttpMethod.GET.matches(metodo) || HttpMethod.HEAD.matches(metodo)
			|| HttpMethod.PUT.matches(metodo) || HttpMethod.DELETE.matches(metodo)
			|| HttpMethod.POST.matches(metodo);
		if (conhecido) {
			chain.doFilter(request, response);
			return;
		}
		response.setStatus(405);
		response.setHeader("Allow", ALLOW_DA_ROTA);
		response.setContentType("application/xml; charset=utf-8");
		response.getWriter().write(S3GatewayController.errorXml("MethodNotAllowed",
			"O metodo " + metodo + " nao e suportado nesta rota do gateway.", caminho));
	}

}