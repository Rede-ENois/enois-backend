package com.socialcoisa.rede.network.adapters.in.web;

import java.io.IOException;
import java.util.UUID;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Sorteia o {@code x-amz-request-id} do gateway S3 <b>uma vez</b> por requisição.
 *
 * <p><b>Antes:</b> nenhuma resposta do gateway tinha o cabeçalho, e o XML de erro saía sem
 * {@code <RequestId>}. O SDK mostra esse id na mensagem de erro ({@code ... (Request ID: ...)}) e é
 * por ele que o suporte acha a requisição no log; sem o cabeçalho, o corpo e o cabeçalho da mesma
 * resposta nem concordavam.</p>
 *
 * <p><b>Agora:</b> o id é gerado aqui, guardado no atributo do pedido e escrito no cabeçalho antes
 * de a resposta começar. O controller e o handler de exceção leem o <b>mesmo</b> atributo, então o
 * {@code <RequestId>} do corpo é sempre o do cabeçalho. Fora de {@code /s3} nenhuma resposta muda.</p>
 *
 * <p>Só o cabeçalho — não o corpo — é escrito aqui: o handler de exceção precisa do atributo antes de
 * montar o XML, e é ele que roda depois deste filtro.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class S3RequestIdFilter extends OncePerRequestFilter {

	/** O cabeçalho que o SDK lê e mostra. */
	static final String HEADER = "x-amz-request-id";

	/** O atributo que leva o id do filtro ao controller e ao handler. */
	static final String ATRIBUTO = S3RequestIdFilter.class.getName() + ".requestId";

	/** O id da requisição deste pedido, ou um novo se o filtro não rodou (chamada direta no teste). */
	public static String idDa(HttpServletRequest request) {
		if (request == null) {
			return novo();
		}
		Object atual = request.getAttribute(ATRIBUTO);
		if (atual instanceof String texto && !texto.isBlank()) {
			return texto;
		}
		String id = novo();
		request.setAttribute(ATRIBUTO, id);
		return id;
	}

	/** A mesma forma que a AWS: 16 caracteres hexadecimais maiúsculos. */
	private static String novo() {
		return UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		// O gateway mora exatamente em `/s3`: `/s3` e `/s3/...`; nada de `contains`, que pegaria
		// `/api/relatorios/s3-backup` e poria o cabecalho do S3 numa resposta que nao e dele.
		String uri = request.getRequestURI();
		return uri == null || !(uri.equals("/s3") || uri.startsWith("/s3/"));
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		String id = idDa(request);
		response.setHeader(HEADER, id);
		filterChain.doFilter(request, response);
	}
}