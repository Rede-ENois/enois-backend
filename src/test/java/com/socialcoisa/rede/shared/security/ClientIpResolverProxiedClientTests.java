package com.socialcoisa.rede.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Quem está atendendo o pedido: a conexão, o salto que o proxy gravou, ou o navegador que o front
 * repassou.
 *
 * <p>Os limitadores por origem precisam da pessoa, não do endereço do servidor que renderiza a
 * tela: todas as contas que usam o mesmo front dividiam um balde só. Mas o valor do front é um
 * cabeçalho, e cabeçalho é do cliente — então ele só vale quando o pedido chegou da conexão de um
 * proxy nosso e a origem declarada é a de um dos NOSSOS fronts (HTTPS). Fora disso o resolvedor
 * devolve o salto que o proxy gravou, como sempre fez.</p>
 */
class ClientIpResolverProxiedClientTests {

	private static final String FRONT_ORIGIN = "https://enois-qa.b7cloud.tech";
	private static final String BROWSER_IP = "203.0.113.20";
	/** Salto do proxy da frente (Cloudflare) quando a cadeia tem mais de um item. */
	private static final String EDGE_IP = "172.69.1.2";
	/** Cadeia de dois saltos: proxy da frente + item do front. */
	private static final String TWO_HOPS = EDGE_IP + ", " + BROWSER_IP;

	private final ClientIpResolver resolver = ClientIpResolver.withRepassingFronts(FRONT_ORIGIN);

	private static MockHttpServletRequest request(String remoteAddr, String origin, String forwardedFor) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/signup");
		request.setRemoteAddr(remoteAddr);
		if (origin != null) {
			request.addHeader("Origin", origin);
		}
		if (forwardedFor != null) {
			request.addHeader("X-Forwarded-For", forwardedFor);
		}
		return request;
	}

	@Test
	void verifiedFrontRepassesTheBrowser() {
		assertThat(resolver.resolve(request("10.0.0.5", FRONT_ORIGIN, "198.51.100.30, " + TWO_HOPS)))
			.isEqualTo(BROWSER_IP);
	}

	@Test
	void anOriginOutsideOurFrontsIsIgnored() {
		// Origem de terceiro (ou front não configurado): nada garante que o item do meio veio do
		// nosso front, então vale o salto que o proxy gravou.
		assertThat(resolver.resolve(request("10.0.0.5", "https://qualquer.example", "198.51.100.30, " + TWO_HOPS)))
			.isEqualTo(BROWSER_IP);
		assertThat(resolver.resolve(request("10.0.0.5", "https://teste-validacao.b7cloud.tech",
			"198.51.100.30, " + TWO_HOPS))).isEqualTo(BROWSER_IP);
	}

	@Test
	void withoutARepassedAddressTheNetworkIsUsed() {
		// Página aberta sem navegador associado: o front não acrescentou nada e o pedido conta na
		// rede de onde veio, não numa identidade inventada.
		assertThat(resolver.resolve(request("203.0.113.99", FRONT_ORIGIN, "198.51.100.30, " + TWO_HOPS)))
			.isEqualTo("203.0.113.99");
	}

	@Test
	void aDirectClientCannotForgeTheRepass() {
		// Conexão direta (não é proxy nosso): origem e cabeçalho não mudam a resposta — o IP é o
		// da própria conexão.
		assertThat(resolver.resolve(request("198.51.100.9", FRONT_ORIGIN, "198.51.100.30, " + TWO_HOPS)))
			.isEqualTo("198.51.100.9");
	}

	@Test
	void withoutAConfiguredFrontOnlyTheLastHopCounts() {
		ClientIpResolver withoutFront = new ClientIpResolver();

		assertThat(withoutFront.resolve(request("10.0.0.5", FRONT_ORIGIN, "198.51.100.30, " + TWO_HOPS)))
			.isEqualTo(BROWSER_IP);
	}

	@Test
	void impossibleRepassedValueIsNeverUsed() {
		assertThat(resolver.resolve(request("10.0.0.5", FRONT_ORIGIN, "198.51.100.30, " + EDGE_IP + ", not-an-ip")))
			.isEqualTo(EDGE_IP);
		assertThat(resolver.resolve(request("10.0.0.5", FRONT_ORIGIN,
			"198.51.100.30, " + EDGE_IP + ", " + "9".repeat(60)))).isEqualTo(EDGE_IP);
	}

	@Test
	void theNetworkAddressStaysTheSameForLockouts() {
		// Bloqueio de rede e limite global continuam na conexão/salto: o repasse do front só serve
		// aos limites de identidade.
		MockHttpServletRequest request = request("10.0.0.5", FRONT_ORIGIN, "198.51.100.30, " + TWO_HOPS);

		assertThat(resolver.resolve(request)).isEqualTo(BROWSER_IP);
		assertThat(resolver.resolveNetwork(request)).isEqualTo(BROWSER_IP);
	}

	@Test
	void anExplicitTrailingHopThatIsATrustedProxyIsSkipped() {
		// Depois do item do front ainda há um proxy nosso: pula e fica com o navegador.
		assertThat(resolver.resolve(request("10.0.0.5", FRONT_ORIGIN,
			"198.51.100.30, " + BROWSER_IP + ", 10.1.1.1"))).isEqualTo(BROWSER_IP);
	}
}