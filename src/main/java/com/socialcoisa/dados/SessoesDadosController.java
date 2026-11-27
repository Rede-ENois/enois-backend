package com.socialcoisa.dados;

import java.io.IOException;
import java.net.InetAddress;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.identity.adapters.in.web.CadastroHttp;
import com.socialcoisa.rede.identity.adapters.in.web.LoginRequest;
import com.socialcoisa.rede.identity.adapters.in.web.SessaoHttp;
import com.socialcoisa.rede.identity.adapters.in.web.SignupRequest;
import com.socialcoisa.rede.identity.application.AuthResponse;
import com.socialcoisa.rede.identity.application.SignupChallengeService.Challenge;
import com.socialcoisa.rede.identity.application.port.in.VerifyEmailUseCase;
import com.socialcoisa.rede.shared.security.IpRateLimiter;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.SessionCookies;
import com.socialcoisa.rede.shared.web.StatusResponse;

import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@RestController
class SessoesDadosController {

	private static final int CORPO_MAXIMO = 16 * 1024;
	private final CredencialDados credencial;
	private final CadastroHttp cadastro;
	private final VerifyEmailUseCase email;
	private final IpRateLimiter limiteIp;
	private final SessaoHttp sessoes;
	private final SessionCookies cookies;
	private final ObjectMapper json;

	SessoesDadosController(CredencialDados credencial, SessaoHttp sessoes, CadastroHttp cadastro,
			VerifyEmailUseCase email, IpRateLimiter limiteIp, SessionCookies cookies, ObjectMapper json) {
		this.credencial = credencial;
		this.sessoes = sessoes;
		this.cadastro = cadastro;
		this.email = email;
		this.limiteIp = limiteIp;
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
		protocolo(grant, resposta);
		byte[] corpo = corpo(requisicao);
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

	@GetMapping("/internal/data/v1/browser/config")
	CadastroHttp.Configuracao configuracao(
			@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String grant,
			HttpServletResponse resposta) {
		protocolo(grant, resposta);
		return cadastro.configuracao(false);
	}

	@GetMapping("/internal/data/v1/browser/signup-challenge")
	Challenge desafio(
			@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String grant,
			@RequestHeader(value = "X-Session-Token", required = false) String sessao,
			@RequestHeader(value = "X-Device-Fingerprint", required = false) String dispositivo,
			@RequestHeader(value = "X-Enois-Client-IP", required = false) String ip,
			HttpServletResponse resposta) {
		protocolo(grant, resposta);
		limitar(IpRateLimiter.ISSUE_CHALLENGE, endereco(ip));
		return cadastro.desafio(sessao, dispositivo);
	}

	@PostMapping("/internal/data/v1/browser/signup")
	@RateLimiter(name = "auth")
	ResponseEntity<String> cadastrar(
			@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String grant,
			@RequestHeader(value = "X-Session-Token", required = false) String sessao,
			@RequestHeader(value = "Idempotency-Key", required = false) String chave,
			@RequestHeader(value = "X-Device-Fingerprint", required = false) String dispositivo,
			@RequestHeader(value = "X-Enois-Client-IP", required = false) String ip,
			HttpServletRequest requisicao, HttpServletResponse resposta) throws IOException {
		protocolo(grant, resposta);
		String origem = endereco(ip);
		limitar(IpRateLimiter.SIGNUP, origem);
		return cadastro.cadastrar(ler(corpo(requisicao), SignupRequest.class), chave, sessao, dispositivo, origem);
	}

	@PostMapping("/internal/data/v1/browser/email/verify")
	@RateLimiter(name = "auth")
	ResponseEntity<?> confirmarEmail(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String grant,
			HttpServletRequest requisicao, HttpServletResponse resposta) throws IOException {
		protocolo(grant, resposta);
		var pedido = ler(corpo(requisicao), ConfirmacaoEmail.class);
		return emitir(ResponseEntity.ok(email.verifyEmail(pedido.token())), resposta);
	}

	@PostMapping("/internal/data/v1/browser/email/resend")
	@RateLimiter(name = "email-sending")
	ResponseEntity<StatusResponse> reenviarEmail(
			@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String grant,
			@RequestHeader(value = "X-Enois-Client-IP", required = false) String ip,
			HttpServletRequest requisicao, HttpServletResponse resposta) throws IOException {
		protocolo(grant, resposta);
		limitar(IpRateLimiter.EMAIL, endereco(ip));
		var pedido = ler(corpo(requisicao), ReenvioEmail.class);
		email.resendVerification(pedido.email());
		return ResponseEntity.accepted().body(new StatusResponse(null, "VERIFICATION_SENT"));
	}

	private void protocolo(String grant, HttpServletResponse resposta) {
		credencial.exigirReplica(grant);
		resposta.setHeader("X-Enois-Data-Protocol", "1");
		resposta.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
	}

	private static byte[] corpo(HttpServletRequest requisicao) throws IOException {
		byte[] corpo = requisicao.getInputStream().readNBytes(CORPO_MAXIMO + 1);
		if (corpo.length > CORPO_MAXIMO) {
			throw new ApiException(413, "PAYLOAD_TOO_LARGE", "Requisição de sessão excede o limite.");
		}
		return corpo;
	}

	private void limitar(String acao, String ip) {
		if (!limiteIp.tryAcquire(acao, ip)) {
			throw new ApiException(429, "RATE_LIMITED", "Muitas tentativas em sequencia. Espere e tente de novo.")
				.withRetryAfter(300);
		}
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

	private record ConfirmacaoEmail(String token) {}

	private record ReenvioEmail(String email) {}
}
