package com.socialcoisa.dados;

import java.io.IOException;
import java.net.InetAddress;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.identity.adapters.in.web.LoginRequest;
import com.socialcoisa.rede.identity.adapters.in.web.SessaoHttp;
import com.socialcoisa.rede.identity.application.AuthResponse;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.SessionCookies;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@RestController
class SessoesDadosController {

	private static final int CORPO_MAXIMO = 16 * 1024;
	private final CredencialDados credencial;
	private final SessaoHttp sessoes;
	private final SessionCookies cookies;
	private final ObjectMapper json;

	SessoesDadosController(CredencialDados credencial, SessaoHttp sessoes, SessionCookies cookies, ObjectMapper json) {
		this.credencial = credencial;
		this.sessoes = sessoes;
		this.cookies = cookies;
		this.json = json;
	}

	@PostMapping("/internal/data/v1/browser/{operacao:login|refresh|logout|verify}")
	ResponseEntity<?> executar(@PathVariable String operacao,
			@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String grant,
			@RequestHeader(value = "X-Session-Token", required = false) String token,
			@RequestHeader(value = "X-Enois-Refresh-Token", required = false) String refresh,
			@RequestHeader(value = "Idempotency-Key", required = false) String chave,
			@RequestHeader(value = "X-Device-Fingerprint", required = false) String dispositivo,
			@RequestHeader(value = "X-Enois-Client-IP", required = false) String ip,
			HttpServletRequest requisicao, HttpServletResponse resposta) throws IOException {
		credencial.exigirReplica(grant);
		resposta.setHeader("X-Enois-Data-Protocol", "1");
		resposta.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
		byte[] corpo = requisicao.getInputStream().readNBytes(CORPO_MAXIMO + 1);
		if (corpo.length > CORPO_MAXIMO) {
			throw new ApiException(413, "PAYLOAD_TOO_LARGE", "Requisição de sessão excede o limite.");
		}
		return switch (operacao) {
			case "login" -> emitir(sessoes.login(ler(corpo, LoginRequest.class), dispositivo, endereco(ip)), resposta);
			case "verify" -> {
				var desafio = ler(corpo, Verificacao.class);
				yield emitir(ResponseEntity.ok(sessoes.verificar(desafio.challengeId(), desafio.code(),
					dispositivo, endereco(ip))), resposta);
			}
			case "refresh" -> {
				var pedido = corpo.length == 0 ? null : ler(corpo, Renovacao.class);
				String anterior = pedido != null && StringUtils.hasText(pedido.refreshToken())
					? pedido.refreshToken() : refresh;
				yield emitir(ResponseEntity.ok(sessoes.renovar(anterior)), resposta);
			}
			case "logout" -> {
				cookies.clear().forEach(valor -> resposta.addHeader(HttpHeaders.SET_COOKIE, valor));
				yield sessoes.logout(token, refresh, chave);
			}
			default -> throw new ApiException(404, "NOT_FOUND", "Operação de sessão desconhecida.");
		};
	}

	private <T> T ler(byte[] corpo, Class<T> tipo) {
		try {
			T pedido = json.readValue(corpo, tipo);
			if (pedido != null) {
				return pedido;
			}
		} catch (JacksonException ignored) {
			// O corpo da sessão contém credenciais e não deve aparecer em erros.
		}
		throw new ApiException(400, "BAD_REQUEST", "Corpo de sessão inválido.");
	}

	private static String endereco(String ip) {
		try {
			if (ip != null && ip.length() <= 45 && !ip.contains("%")) {
				return InetAddress.ofLiteral(ip).getHostAddress();
			}
		} catch (IllegalArgumentException ignored) {
			// A identificação de rede é fornecida pelo ingresso, nunca pelo navegador.
		}
		throw new ApiException(503, "INGRESS_CONTEXT_REQUIRED", "Origem de rede indisponível.");
	}

	private ResponseEntity<?> emitir(ResponseEntity<?> resultado, HttpServletResponse resposta) {
		if (resultado.getBody() instanceof AuthResponse sessao) {
			var valores = sessao.twoFactor() == null
				? cookies.issue(sessao.token(), sessao.expiresAt(), sessao.refreshToken(), sessao.refreshExpiresAt())
				: cookies.challenge(sessao.twoFactor().challengeId(), sessao.twoFactor().expiresAt());
			valores.forEach(valor -> resposta.addHeader(HttpHeaders.SET_COOKIE, valor));
		}
		return resultado;
	}

	private record Renovacao(String refreshToken) {}

	private record Verificacao(String challengeId, String code) {}
}
