package com.socialcoisa.rede.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.FilterChain;

/**
 * A defesa contra varredura de rotas.
 *
 * <p>Um scanner erra de propósito: 404 atrás de 404, 401 ao tentar endpoints privados, 400 ao
 * mandar payload inválido. O limitador global conta requisições e não vê diferença entre isso e
 * alguém rolando o feed. Aqui a conta é por ERRO numa janela curta, e o preço é a negação com
 * prazo (429 + Retry-After) — que escala na reincidência e expira sozinha.
 *
 * <p>A linha fina: quem usa o app produz 200 e, às vezes, um 409 de estado (e-mail já cadastrado).
 * Nada disso conta. E o health check do cluster nunca entra: derrubá-lo derruba o pod.
 */
class AbuseGuardFilterTests {

	/** Relógio que anda quando o teste manda. */
	private static final class RelogioMovel extends Clock {
		private Instant agora = Instant.parse("2026-10-22T12:00:00Z");

		@Override
		public ZoneOffset getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(java.time.ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return agora;
		}

		void avancarMinutos(long minutos) {
			agora = agora.plusSeconds(minutos * 60);
		}
	}

	private final RelogioMovel relogio = new RelogioMovel();
	private final ClientIpResolver ipResolver = new ClientIpResolver();
	// Teto baixo para o teste fechar rápido; os padrões do app são maiores (20 erros em 3 min).
	private final AbuseGuardFilter filtro = new AbuseGuardFilter(relogio, ipResolver, 3, 180, 15, 360);

	private static MockHttpServletRequest pedido(String ip, String uri) {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
		request.setRemoteAddr(ip);
		return request;
	}

	/** Executa a cadeia devolvendo o status informado (o que o controller faria). */
	private static FilterChain respondendo(int status) {
		return (request, response) -> ((MockHttpServletResponse) response).setStatus(status);
	}

	private int chamar(String ip, String uri, int status) throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		filtro.doFilter(pedido(ip, uri), response, respondendo(status));
		return response.getStatus();
	}


	/**
	 * O 429 tem de vir com o cabecalho de CORS: sem ele o navegador entrega `NetworkError` e a
	 * pessoa ve "erro de rede" em vez de "muitas requisicoes invalidas" (visto na QA em 2026-10-23).
	 */
	@Test
	void negacaoDevolveCabecalhoDeCorsDaOrigem() throws Exception {
		String ip = "198.51.100.77";
		for (int i = 0; i < 3; i++) {
			chamar(ip, "/wp-admin", 404);
		}

		MockHttpServletRequest request = pedido(ip, "/api/feed");
		request.addHeader("Origin", "https://enois-qa.b7cloud.tech");
		MockHttpServletResponse response = new MockHttpServletResponse();
		filtro.doFilter(request, response, respondendo(200));

		assertThat(response.getStatus()).isEqualTo(429);
		assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo("https://enois-qa.b7cloud.tech");
		assertThat(response.getHeader("Vary")).isEqualTo("Origin");
	}

	@Test
	void varreduraDeRotasAcabaEmNegacaoComPrazo() throws Exception {
		String ip = "203.0.113.10";

		// As tres primeiras erradas passam (o teto e 3); na terceira o bloqueio e armado, e da
		// quarta em diante TUDO vira 429 — inclusive a rota certa.
		assertThat(chamar(ip, "/wp-admin", 404)).isEqualTo(404);
		assertThat(chamar(ip, "/.env", 404)).isEqualTo(404);
		assertThat(chamar(ip, "/admin/config", 404)).isEqualTo(404);
		assertThat(chamar(ip, "/api/v1/qualquer", 404)).isEqualTo(429);

		// Daqui em diante, mesmo uma rota boa é negada — o IP está bloqueado.
		MockHttpServletResponse negado = new MockHttpServletResponse();
		filtro.doFilter(pedido(ip, "/feed"), negado, respondendo(200));
		assertThat(negado.getStatus()).isEqualTo(429);
		assertThat(negado.getHeader("Retry-After")).isNotBlank();
		assertThat(negado.getContentAsString()).contains("RATE_LIMITED");
	}

	@Test
	void oBloqueioExpiraSozinho() throws Exception {
		String ip = "203.0.113.20";
		for (int i = 0; i < 3; i++) {
			chamar(ip, "/rota-inexistente-" + i, 404);
		}
		assertThat(chamar(ip, "/feed", 200)).isEqualTo(429);

		// O primeiro bloqueio é de 15 minutos: passado o prazo, a pessoa volta a ser atendida.
		relogio.avancarMinutos(16);
		MockHttpServletResponse depois = new MockHttpServletResponse();
		filtro.doFilter(pedido(ip, "/feed"), depois, respondendo(200));
		assertThat(depois.getStatus()).isEqualTo(200);
	}

	@Test
	void reincidenciaBloqueiaPorMaisTempo() throws Exception {
		String ip = "203.0.113.30";
		for (int i = 0; i < 3; i++) {
			chamar(ip, "/x" + i, 404);
		}
		// Passa o primeiro prazo (15 min) e ele volta a varrer: o segundo bloqueio é maior.
		relogio.avancarMinutos(16);
		for (int i = 0; i < 3; i++) {
			chamar(ip, "/y" + i, 404);
		}
		relogio.avancarMinutos(20);
		MockHttpServletResponse dentroDoSegundo = new MockHttpServletResponse();
		filtro.doFilter(pedido(ip, "/feed"), dentroDoSegundo, respondendo(200));
		assertThat(dentroDoSegundo.getStatus()).isEqualTo(429);
	}

	@Test
	void usoNormalNaoConta() throws Exception {
		String ip = "203.0.113.40";
		for (int i = 0; i < 10; i++) {
			assertThat(chamar(ip, "/feed", 200)).isEqualTo(200);
			// 409 de estado (e-mail já cadastrado, conflito de versão) é resposta normal.
			assertThat(chamar(ip, "/auth/signup", 409)).isEqualTo(409);
		}
		assertThat(chamar(ip, "/feed", 200)).isEqualTo(200);
	}

	@Test
	void sondaDoClusterNuncaEntra() throws Exception {
		String ip = "203.0.113.50";
		for (int i = 0; i < 10; i++) {
			assertThat(chamar(ip, "/api/health", 404)).isEqualTo(404);
		}
		assertThat(chamar(ip, "/feed", 200)).isEqualTo(200);
	}
}
