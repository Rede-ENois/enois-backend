package com.socialcoisa.rede.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * O IP que o app enxerga é o da pessoa, nunca um que ela escolheu.
 *
 * <p>O `X-Forwarded-For` é o único header que o cliente controla: ele pode mandar qualquer coisa
 * nele. Todo limite por IP (teto de login, de cadastro, de leitura) e o bloqueio da moderação se
 * apoiam no IP resolvido aqui — então o item da cadeia que o cliente forja não pode valer. A
 * leitura é da direita para a esquerda, pulando os proxies confiáveis, e assim serve às duas
 * convenções do proxy: o que substitui o cabeçalho e o que o acrescenta.
 */
class ClientIpSpoofingTests {

	private static final String REAL_IP = "203.0.113.7";

	private final ClientIpResolver resolver = new ClientIpResolver();

	@Test
	void usaOHopQueOProxyAcrescentouEIgnoraOsHeadersDoCliente() {
		// Convenção "acrescenta": o proxy põe o IP real no FIM da lista.
		MockHttpServletRequest request = albRequest();
		request.addHeader("X-Real-IP", "6.6.6.6");

		assertThat(resolver.resolve(request)).isEqualTo(REAL_IP);
	}

	@Test
	void oItemQueOClienteForjaNaoVale() {
		// O cliente manda o próprio XFF: o proxy acrescenta o IP real no fim. O item forjado (à
		// esquerda) é o único que a pessoa controla — e é o que não pode valer.
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/signup");
		request.addHeader("X-Forwarded-For", "9.9.9.9, " + REAL_IP);
		request.setRemoteAddr("10.0.0.5");

		assertThat(resolver.resolve(request)).isEqualTo(REAL_IP);
	}

	@Test
	void oCabecalhoDoCloudflareTemPrecedencia() {
		// O Cloudflare fica na frente do proxy e reescreve o X-Forwarded-For com o endereco da
		// BORDA dele (nos logs: faixas 172.6x para todo mundo). O IP real da pessoa vem no
		// CF-Connecting-IP, que o Cloudflare sobrescreve a cada requisicao — o cliente nao forja.
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/feed");
		request.addHeader("CF-Connecting-IP", REAL_IP);
		request.addHeader("X-Forwarded-For", "172.69.1.2");
		request.setRemoteAddr("10.0.0.5");

		assertThat(resolver.resolve(request)).isEqualTo(REAL_IP);
	}

	@Test
	void oCabecalhoDoCloudflareSoValeComingDeUmProxyConfiavel() {
		// Conexao direta (nao passou pelo proxy da nossa rede): o header nao vale e o IP e o da
		// conexao — senao qualquer cliente se identificaria como quem quiser.
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/feed");
		request.addHeader("CF-Connecting-IP", REAL_IP);
		request.setRemoteAddr("198.51.100.9");

		assertThat(resolver.resolve(request)).isEqualTo("198.51.100.9");
	}

	@Test
	void oProxyQueSubstituiOCabecalhoTambemServe() {
		// Convenção "substitui": a cadeia tem um item só, o IP real.
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/feed");
		request.addHeader("X-Forwarded-For", REAL_IP);
		request.setRemoteAddr("10.0.0.5");

		assertThat(resolver.resolve(request)).isEqualTo(REAL_IP);
	}

	@Test
	void umProxyConfiavelNoMeioDaCadeiaEPulado() {
		// "forjado, real, proxy-confiavel": o confiável é pulado e vale o primeiro que não é.
		ClientIpResolver comProxy = new ClientIpResolver("10.0.0.9");
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/feed");
		request.addHeader("X-Forwarded-For", "9.9.9.9, " + REAL_IP + ", 10.0.0.9");
		request.setRemoteAddr("10.0.0.9");

		assertThat(comProxy.resolve(request)).isEqualTo(REAL_IP);
	}

	@Test
	void cabecalhoInchadoNaoFazOItemForjadoValer() {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/signup");
		request.addHeader("X-Forwarded-For", "1.1.1.1, ".repeat(100) + REAL_IP);
		request.addHeader("X-Real-IP", "6.6.6.6");

		assertThat(resolver.resolve(request)).isEqualTo(REAL_IP);
	}

	@Test
	void hopImpossivelCaiNaConexaoENaoAoHeaderDoCliente() {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/feed");
		request.addHeader("X-Forwarded-For", "not-an-ip");
		request.addHeader("X-Real-IP", "6.6.6.6");
		request.setRemoteAddr("10.0.0.5");

		assertThat(resolver.resolve(request)).isEqualTo("10.0.0.5");
	}

	@Test
	void girarOHeaderDoClienteNaoEscapaDoLimiteGlobal() throws Exception {
		Clock clock = Clock.fixed(Instant.parse("2026-10-20T00:00:00Z"), ZoneOffset.UTC);
		GlobalApiRateLimitFilter filter = new GlobalApiRateLimitFilter(clock, resolver, 3, 50);

		int lastStatus = 0;
		for (int i = 0; i < 4; i++) {
			// Cada requisição "escolhe" um IP diferente no XFF: todas têm de cair no mesmo balde.
			MockHttpServletRequest request = new MockHttpServletRequest("POST", "/feed");
			request.addHeader("X-Forwarded-For", "6.6.6." + i + ", " + REAL_IP);
			MockHttpServletResponse response = new MockHttpServletResponse();
			filter.doFilter(request, response, new MockFilterChain());
			lastStatus = response.getStatus();
		}

		assertThat(lastStatus).isEqualTo(429);
	}

	@Test
	void loggedInUserHasOwnBucketSeparateFromTheIp() throws Exception {
		Clock clock = Clock.fixed(Instant.parse("2026-10-20T00:00:00Z"), ZoneOffset.UTC);
		GlobalApiRateLimitFilter filter = new GlobalApiRateLimitFilter(clock, resolver, 2, 50);

		// Anônimo esgota o balde do IP em 2.
		for (int i = 0; i < 2; i++) {
			MockHttpServletRequest request = albRequest();
			MockHttpServletResponse response = new MockHttpServletResponse();
			filter.doFilter(request, response, new MockFilterChain());
			assertThat(response.getStatus()).isNotEqualTo(429);
		}
		MockHttpServletRequest blocked = albRequest();
		MockHttpServletResponse blockedResponse = new MockHttpServletResponse();
		filter.doFilter(blocked, blockedResponse, new MockFilterChain());
		assertThat(blockedResponse.getStatus()).isEqualTo(429);

		// Logado usa o balde da sessão: o IP lotado não o atinge.
		MockHttpServletRequest logged = albRequest();
		logged.addHeader("X-Session-Token", "token-user-a");
		MockHttpServletResponse loggedResponse = new MockHttpServletResponse();
		filter.doFilter(logged, loggedResponse, new MockFilterChain());
		assertThat(loggedResponse.getStatus()).isNotEqualTo(429);
	}

	/** POST: o teto global agora vale para escrita (leitura é livre). */
	private static MockHttpServletRequest albRequest() {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/feed");
		request.addHeader("X-Forwarded-For", "9.9.9.9, " + REAL_IP);
		return request;
	}
}
