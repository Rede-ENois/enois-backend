package com.socialcoisa.replica;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.shared.web.ErrorResponse;
import com.socialcoisa.rede.shared.web.SessionCookies;

@RestController
class ApiReplicaController {

	private static final int CORPO_MAXIMO = 1024 * 1024;
	private static final Set<Integer> STATUS_PUBLICOS = Set.of(200, 201, 400, 401, 403, 404, 409, 413, 429);
	private final ClienteDados dados;

	ApiReplicaController(ClienteDados dados) {
		this.dados = dados;
	}

	@PostMapping(value = "/feed/posts", consumes = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<?> publicar(@RequestHeader(value = "X-Session-Token", required = false) String token,
			@RequestHeader(value = "Idempotency-Key", required = false) String chave,
			@RequestHeader(value = "X-CSRF-Protection", required = false) String csrf,
			@CookieValue(value = SessionCookies.SESSION, required = false) String cookie,
			@CookieValue(value = SessionCookies.REFRESH, required = false) String refresh,
			HttpServletRequest requisicao) {
		if (!StringUtils.hasText(token) && (StringUtils.hasText(cookie) || StringUtils.hasText(refresh))
				&& !"1".equals(csrf)) {
			return ResponseEntity.status(403).cacheControl(CacheControl.noStore()).body(Map.of(
				"code", "CSRF_REQUIRED", "message", "Requisicao recusada. Recarregue a pagina e tente de novo."));
		}
		try {
			byte[] corpo = requisicao.getInputStream().readNBytes(CORPO_MAXIMO + 1);
			if (corpo.length > CORPO_MAXIMO) {
				return ResponseEntity.status(413).cacheControl(CacheControl.noStore())
					.body(new ErrorResponse("PAYLOAD_TOO_LARGE", "Publicação excede o limite de tamanho.", Instant.now()));
			}
			return respostaPublica(dados.publicar(corpo, StringUtils.hasText(token) ? token : cookie, chave));
		} catch (InterruptedException falha) {
			Thread.currentThread().interrupt();
		} catch (IOException | IllegalArgumentException ignored) {
			// A fronteira é indisponível quando o agente perde a atribuição.
		}
		return indisponivel();
	}

	@GetMapping("/feed/posts/{postId}")
	ResponseEntity<?> post(@PathVariable String postId,
			@RequestHeader(value = "X-Session-Token", required = false) String token,
			@CookieValue(value = SessionCookies.SESSION, required = false) String cookie) {
		try {
			var resposta = dados.post(postId, StringUtils.hasText(token) ? token : cookie);
			return respostaPublica(resposta);
		} catch (InterruptedException failure) {
			Thread.currentThread().interrupt();
		} catch (IOException | IllegalArgumentException ignored) {
			// Erros da fronteira de dados têm a mesma resposta pública que falta de atribuição.
		}
		return indisponivel();
	}

	@GetMapping("/auth/me")
	ResponseEntity<?> sessao(@RequestHeader(value = "X-Session-Token", required = false) String token,
			@CookieValue(value = SessionCookies.SESSION, required = false) String cookie) {
		try {
			return respostaPublica(dados.sessao(StringUtils.hasText(token) ? token : cookie));
		} catch (InterruptedException failure) {
			Thread.currentThread().interrupt();
		} catch (IOException | IllegalArgumentException ignored) {
			// Um erro na autenticação do workload é indisponibilidade dos dados para o usuário.
		}
		return indisponivel();
	}

	private static ResponseEntity<?> respostaPublica(HttpResponse<byte[]> resposta) {
		int codigo = resposta.statusCode();
		if (STATUS_PUBLICOS.contains(codigo)
				&& resposta.headers().firstValue("X-Enois-Data-Protocol").filter("1"::equals).isPresent()) {
			var publica = ResponseEntity.status(codigo).cacheControl(CacheControl.noStore());
			resposta.headers().firstValue(HttpHeaders.RETRY_AFTER)
				.ifPresent(valor -> publica.header(HttpHeaders.RETRY_AFTER, valor));
			return publica
				.contentType(MediaType.APPLICATION_JSON).body(resposta.body());
		}
		return indisponivel();
	}

	private static ResponseEntity<ErrorResponse> indisponivel() {
		return ResponseEntity.status(503).cacheControl(CacheControl.noStore())
			.body(new ErrorResponse("DATA_UNAVAILABLE", "Serviço de dados indisponível.", Instant.now()));
	}
}
