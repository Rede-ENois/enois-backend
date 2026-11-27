package com.socialcoisa.dados;

import java.util.EnumSet;
import java.util.Set;

import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.socialcoisa.rede.feed.adapters.in.web.ConsultaFeedHttp;
import com.socialcoisa.rede.feed.adapters.in.web.FeedResponse;
import com.socialcoisa.rede.feed.domain.FeedTab;
import com.socialcoisa.rede.feed.domain.PostType;
import com.socialcoisa.rede.notifications.adapters.in.web.NotificacoesHttp;
import com.socialcoisa.rede.notifications.adapters.in.web.NotificacoesHttp.NotificationView;
import com.socialcoisa.rede.notifications.application.NotificationService;
import com.socialcoisa.rede.shared.security.PanicModeService;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.PageResponse;

@RestController
@RequestMapping("/internal/data/v1")
class NavegadorDadosController {

	private final CredencialDados credencial;
	private final ConsultaFeedHttp feed;
	private final NotificacoesHttp avisos;
	private final PanicModeService panico;

	NavegadorDadosController(CredencialDados credencial, ConsultaFeedHttp feed, NotificacoesHttp avisos,
			PanicModeService panico) {
		this.credencial = credencial;
		this.feed = feed;
		this.avisos = avisos;
		this.panico = panico;
	}

	@GetMapping("/feed")
	FeedResponse feed(HttpServletRequest pedido, HttpServletResponse resposta) {
		protocolo(pedido, resposta);
		return feed.consultar(sessao(pedido), pedido.getParameter("cursor"), limite(pedido),
			parametro(pedido, "tab", FeedTab.class, FeedTab.FOR_YOU), tipos(pedido.getParameterValues("type")),
			parametro(pedido, "resolved", Boolean.class, null));
	}

	@GetMapping("/notifications")
	PageResponse<NotificationView> avisos(HttpServletRequest pedido, HttpServletResponse resposta) {
		protocolo(pedido, resposta);
		return avisos.notifications(sessao(pedido), pedido.getParameter("cursor"), limite(pedido),
			pedido.getParameter("context"));
	}

	@GetMapping("/notifications/unread-summary")
	NotificationService.UnreadSummary naoLidos(HttpServletRequest pedido, HttpServletResponse resposta) {
		protocolo(pedido, resposta);
		return avisos.unreadSummary(sessao(pedido), pedido.getParameter("context"));
	}

	@PostMapping("/notifications/{notificationId}/read")
	ResponseEntity<String> ler(@PathVariable String notificationId, HttpServletRequest pedido,
			HttpServletResponse resposta) {
		protocolo(pedido, resposta);
		permiteEscrita();
		return avisos.read(sessao(pedido), pedido.getHeader("Idempotency-Key"), notificationId);
	}

	@PostMapping("/notifications/read-all")
	ResponseEntity<String> lerTudo(HttpServletRequest pedido, HttpServletResponse resposta) {
		protocolo(pedido, resposta);
		permiteEscrita();
		return avisos.readAll(sessao(pedido), pedido.getHeader("Idempotency-Key"));
	}

	private void protocolo(HttpServletRequest pedido, HttpServletResponse resposta) {
		credencial.exigirReplica(pedido.getHeader(HttpHeaders.AUTHORIZATION));
		resposta.setHeader("X-Enois-Data-Protocol", "1");
		resposta.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
	}

	private void permiteEscrita() {
		if (panico.active()) {
			throw new ApiException(429, "PANIC_MODE", "Escritas temporariamente bloqueadas pelo modo panico.");
		}
	}

	private static String sessao(HttpServletRequest pedido) {
		return pedido.getHeader("X-Session-Token");
	}

	private static int limite(HttpServletRequest pedido) {
		return parametro(pedido, "limit", Integer.class, 20);
	}

	private static <T> T parametro(HttpServletRequest pedido, String nome, Class<T> tipo, T padrao) {
		String valor = pedido.getParameter(nome);
		if (valor == null || valor.isBlank()) {
			return padrao;
		}
		try {
			return DefaultConversionService.getSharedInstance().convert(valor, tipo);
		} catch (IllegalArgumentException | org.springframework.core.convert.ConversionException falha) {
			throw new ApiException(400, "BAD_REQUEST", "Parametro de consulta invalido.");
		}
	}

	private static Set<PostType> tipos(String[] valores) {
		var tipos = EnumSet.noneOf(PostType.class);
		if (valores == null) {
			return tipos;
		}
		try {
			for (String valor : valores) {
				for (String tipo : valor.split(",")) {
					if (!tipo.isBlank()) {
						tipos.add(PostType.valueOf(tipo.trim()));
					}
				}
			}
			return tipos;
		} catch (IllegalArgumentException falha) {
			throw new ApiException(400, "BAD_REQUEST", "Tipo de publicacao invalido.");
		}
	}
}
