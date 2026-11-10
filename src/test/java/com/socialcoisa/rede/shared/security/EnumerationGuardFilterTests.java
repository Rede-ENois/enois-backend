package com.socialcoisa.rede.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.FilterChain;

/**
 * O filtro que centraliza a proteção: shadow mode observa sem nunca negar, enforce nega com
 * bloqueio progressivo e temporário.
 *
 * <p>O contrato que importa: no modo padrão (<em>shadow</em>) nenhuma requisição legítima é
 * negada, mesmo que a pontuação dispare — é o que permite validar os limiares com tráfego real
 * antes de ligar o bloqueio.</p>
 */
class EnumerationGuardFilterTests {

	private final Clock clock = Clock.fixed(Instant.parse("2026-11-09T12:00:00Z"), ZoneOffset.UTC);

	private EnumerationGuardFilter filter(String mode, long firstBlockMinutes) {
		EnumerationRiskEngine engine = new EnumerationRiskEngine(clock, 120, 12, 25, 50, 4, 0.6, 8, 4);
		ClientIpResolver ipResolver = new ClientIpResolver("");
		RateLimitSessions sessions = new RateLimitSessions(null, clock, ipResolver, 50);
		return new EnumerationGuardFilter(engine, ipResolver, sessions, clock, mode, firstBlockMinutes, 1440);
	}

	private static MockHttpServletRequest request(String uri) {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
		request.setRequestURI(uri);
		return request;
	}

	private static FilterChain chain(AtomicInteger calls, int status) {
		return (req, res) -> {
			calls.incrementAndGet();
			((MockHttpServletResponse) res).setStatus(status);
		};
	}

	/** Modo shadow: a varredura passa, mas NADA é negado — o time valida os limiares em observes. */
	@Test
	void modoShadowObservaMasNaoBloqueia() throws Exception {
		EnumerationGuardFilter filter = filter("shadow", 5);
		AtomicInteger calls = new AtomicInteger();
		MockHttpServletResponse response = new MockHttpServletResponse();

		for (int i = 1; i <= 40; i++) {
			filter.doFilter(request("/api/users/alvo" + i), response, chain(calls, 404));
		}

		assertThat(calls.get()).as("nenhuma requisição é negada no modo shadow").isEqualTo(40);
		assertThat(response.getStatus()).isEqualTo(404);
		assertThat(filter.activeBlocks()).isEmpty();
	}

	/** Modo enforce: a varredura confirmada é bloqueada. */
	@Test
	void modoEnforceBloqueiaVarreduraConfirmada() throws Exception {
		EnumerationGuardFilter filter = filter("enforce", 5);
		AtomicInteger calls = new AtomicInteger();
		MockHttpServletResponse response = new MockHttpServletResponse();

		for (int i = 1; i <= 60; i++) {
			filter.doFilter(request("/api/users/alvo" + i), response, chain(calls, 404));
		}

		assertThat(filter.activeBlocks()).as("a origem fica bloqueada").isNotEmpty();
		long lastStatus = response.getStatus();
		assertThat(lastStatus).as("a última requisição já entra negada").isEqualTo(429);
	}

	/** Navegação legítima não é bloqueada, nem no modo enforce. */
	@Test
	void navegacaoLegitimaNaoBloqueiaNemNoModoEnforce() throws Exception {
		EnumerationGuardFilter filter = filter("enforce", 5);
		AtomicInteger calls = new AtomicInteger();
		MockHttpServletResponse response = new MockHttpServletResponse();

		for (int i = 0; i < 300; i++) {
			String[] alvos = { "ana", "bruno", "carla" };
			filter.doFilter(request("/api/users/" + alvos[i % alvos.length]), response, chain(calls, 200));
		}

		assertThat(calls.get()).isEqualTo(300);
		assertThat(filter.activeBlocks()).isEmpty();
	}

	/** Rota fora da superfície passa direto, sem custo. */
	@Test
	void rotaForaDaSuperficiePassaDireto() throws Exception {
		EnumerationGuardFilter filter = filter("enforce", 5);
		AtomicInteger calls = new AtomicInteger();
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request("/api/posts"), response, chain(calls, 200));

		assertThat(calls.get()).isEqualTo(1);
		assertThat(response.getStatus()).isEqualTo(200);
	}

	/** Modo off: o filtro nem observa. */
	@Test
	void modoOffNaoFazNada() throws Exception {
		EnumerationGuardFilter filter = filter("off", 5);
		AtomicInteger calls = new AtomicInteger();
		MockHttpServletResponse response = new MockHttpServletResponse();

		for (int i = 1; i <= 60; i++) {
			filter.doFilter(request("/api/users/alvo" + i), response, chain(calls, 404));
		}

		assertThat(calls.get()).isEqualTo(60);
		assertThat(filter.activeBlocks()).isEmpty();
	}

	/** Um valor de modo invalido cai no seguro: observar sem bloquear. */
	@Test
	void modoInvalidoCaiNoShadow() {
		assertThat(filter("banana", 5).mode()).isEqualTo(EnumerationGuardFilter.Mode.SHADOW);
	}

	/** Liberação manual do bloqueio (painel). */
	@Test
	void bloqueioPodeSerLiberadoPeloPainel() throws Exception {
		EnumerationGuardFilter filter = filter("enforce", 5);
		AtomicInteger calls = new AtomicInteger();
		MockHttpServletResponse response = new MockHttpServletResponse();

		for (int i = 1; i <= 60; i++) {
			filter.doFilter(request("/api/users/alvo" + i), response, chain(calls, 404));
		}
		assertThat(filter.activeBlocks()).isNotEmpty();

		List<String> keys = List.copyOf(filter.activeBlocks().keySet());
		assertThat(filter.release(keys.get(0))).isTrue();
		assertThat(filter.activeBlocks()).isEmpty();
	}
}
