package com.socialcoisa.dados;

import java.io.IOException;

import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.socialcoisa.rede.feed.adapters.in.web.PublicacaoPostHttp;
import com.socialcoisa.rede.feed.adapters.in.web.PublishPostRequest;
import com.socialcoisa.rede.feed.application.PostView;
import com.socialcoisa.rede.feed.application.port.in.GetPostUseCase;
import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.identity.application.AuthUserView;
import com.socialcoisa.rede.identity.application.port.in.GetAuthenticatedUserUseCase;
import com.socialcoisa.rede.shared.security.PanicModeService;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/internal/data/v1")
class DadosController {

	private static final int CORPO_MAXIMO = 1024 * 1024;
	private final CredencialDados credencial;
	private final JdbcTemplate jdbc;
	private final GetPostUseCase posts;
	private final CurrentUserResolver usuarios;
	private final GetAuthenticatedUserUseCase sessoes;
	private final PublicacaoPostHttp publicacao;
	private final PanicModeService panico;
	private final ObjectMapper json;

	DadosController(CredencialDados credencial, JdbcTemplate jdbc, GetPostUseCase posts, CurrentUserResolver usuarios,
			GetAuthenticatedUserUseCase sessoes, PublicacaoPostHttp publicacao, PanicModeService panico,
			ObjectMapper json) {
		this.credencial = credencial;
		this.jdbc = jdbc;
		this.posts = posts;
		this.usuarios = usuarios;
		this.sessoes = sessoes;
		this.publicacao = publicacao;
		this.panico = panico;
		this.json = json;
	}

	@GetMapping("/ready")
	ResponseEntity<Void> prontidao(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String grant) {
		credencial.exigirReplica(grant);
		jdbc.queryForObject("select 1", Integer.class);
		return ResponseEntity.noContent().header("X-Enois-Data-Protocol", "1").build();
	}

	@GetMapping("/posts/{postId}")
	PostView post(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String grant,
			@RequestHeader(value = "X-Session-Token", required = false) String sessao, @PathVariable String postId,
			HttpServletResponse resposta) {
		credencial.exigirReplica(grant);
		resposta.setHeader("X-Enois-Data-Protocol", "1");
		try {
			return posts.getPost(postId, visitante(sessao));
		} finally {
			MDC.clear();
		}
	}

	@GetMapping("/session")
	AuthUserView sessao(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String grant,
			@RequestHeader(value = "X-Session-Token", defaultValue = "") String token, HttpServletResponse resposta) {
		credencial.exigirReplica(grant);
		resposta.setHeader("X-Enois-Data-Protocol", "1");
		resposta.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
		return sessoes.getAuthenticatedUser(token);
	}

	@PostMapping(value = "/posts", consumes = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<String> publicar(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String grant,
			@RequestHeader(value = "X-Session-Token", required = false) String sessao,
			@RequestHeader(value = "Idempotency-Key", required = false) String chave,
			HttpServletRequest requisicao, HttpServletResponse resposta) throws IOException {
		credencial.exigirReplica(grant);
		resposta.setHeader("X-Enois-Data-Protocol", "1");
		if (panico.active()) {
			throw new ApiException(429, "PANIC_MODE",
				"Escritas temporariamente bloqueadas pelo modo panico. Tente novamente em instantes.");
		}
		byte[] corpo = requisicao.getInputStream().readNBytes(CORPO_MAXIMO + 1);
		if (corpo.length > CORPO_MAXIMO) {
			throw new ApiException(413, "PAYLOAD_TOO_LARGE", "Publicação excede o limite de tamanho.");
		}
		PublishPostRequest pedido;
		try {
			pedido = json.readValue(corpo, PublishPostRequest.class);
		} catch (tools.jackson.core.JacksonException falha) {
			throw new ApiException(400, "BAD_REQUEST", "Corpo de publicação inválido.");
		}
		if (pedido == null) {
			throw new ApiException(400, "BAD_REQUEST", "Corpo de publicação inválido.");
		}
		try {
			return publicacao.publicar(sessao, chave, pedido);
		} finally {
			MDC.clear();
		}
	}

	private UserId visitante(String sessao) {
		try {
			return usuarios.resolve(sessao).id();
		} catch (ApiException failure) {
			if (failure.status() != 401 && failure.status() != 403) {
				throw failure;
			}
			return null;
		}
	}
}
