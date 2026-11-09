package com.socialcoisa.rede.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

/**
 * O motor de risco separa <em>descoberta de recursos</em> de <em>navegação</em>.
 *
 * <p>Estes testes são o critério de aceitação do pedido: enumeração sequencial de ids, de
 * usernames, enumeração distribuída entre endpoints, bypass por troca de rota e — o mais
 * importante — a navegação legítima de uma pessoa real, que não pode ser bloqueada.</p>
 */
class EnumerationRiskEngineTests {

	private final Clock clock = Clock.fixed(Instant.parse("2026-11-09T12:00:00Z"), ZoneOffset.UTC);

	private EnumerationRiskEngine engine() {
		return new EnumerationRiskEngine(clock, 120, 12, 25, 50, 4, 0.6, 8, 4);
	}

	@Test
	void varreduraSequencialDeUsernamesSobeDeNivel() {
		EnumerationRiskEngine engine = engine();
		EnumerationRiskEngine.Assessment last = null;
		for (int i = 1; i <= 30; i++) {
			last = engine.observe(new EnumerationRiskEngine.Observation("ip:1.2.3.4", "users.byHandle",
				EnumerationSurface.Kind.USERNAME, "usuario" + i, false, 404));
		}
		assertThat(last.level()).isGreaterThanOrEqualTo(EnumerationRiskEngine.Level.HIGH);
		assertThat(last.sequential()).as("ids em sequência são o padrão de quem itera").isTrue();
		assertThat(last.reasons()).isNotEmpty();
	}

	@Test
	void varreduraSequencialDeIdsOpacosTambemSobe() {
		EnumerationRiskEngine engine = engine();
		EnumerationRiskEngine.Assessment last = null;
		for (int i = 1; i <= 30; i++) {
			last = engine.observe(new EnumerationRiskEngine.Observation("ip:1.2.3.4", "posts.byId",
				EnumerationSurface.Kind.ID, "post_" + i, false, 200));
		}
		assertThat(last.level()).isGreaterThanOrEqualTo(EnumerationRiskEngine.Level.HIGH);
		assertThat(last.distinctIdentifiers()).isEqualTo(30);
	}

	@Test
	void proporcaoAltaDeRecursosInexistentesConta() {
		EnumerationRiskEngine engine = engine();
		EnumerationRiskEngine.Assessment last = null;
		for (int i = 1; i <= 20; i++) {
			last = engine.observe(new EnumerationRiskEngine.Observation("ip:9.9.9.9", "users.byHandle",
				EnumerationSurface.Kind.USERNAME, "naoexiste" + i, false, 404));
		}
		assertThat(last.missingRatio()).isEqualTo(1.0);
		assertThat(last.level()).isGreaterThanOrEqualTo(EnumerationRiskEngine.Level.HIGH);
	}

	/**
	 * Bypass por troca de rota: pedir o mesmo alvo em rotas diferentes é a MESMA pessoa
	 * explorando a mesma superfície. A janela é por chave, não por rota — é o que impede o script
	 * de reiniciar a contagem a cada endpoint.
	 */
	@Test
	void trocarDeRotaNaoReiniciaAContagem() {
		EnumerationRiskEngine engine = engine();
		String[] routes = { "users.byHandle", "users.presence", "users.profile" };
		EnumerationRiskEngine.Assessment last = null;
		for (int i = 1; i <= 30; i++) {
			last = engine.observe(new EnumerationRiskEngine.Observation("ip:5.5.5.5", routes[i % routes.length],
				EnumerationSurface.Kind.USERNAME, "alvo" + i, false, 404));
		}
		assertThat(last.distinctIdentifiers()).isEqualTo(30);
		assertThat(last.level()).isGreaterThanOrEqualTo(EnumerationRiskEngine.Level.HIGH);
	}

	/**
	 * Falso positivo: pessoa de verdade navega entre poucos perfis, repete os mesmos alvos e recebe
	 * 200. Nada disso pode virar bloqueio — é o caso que impede o sistema de punir gente honesta.
	 */
	@Test
	void navegacaoLegitimaNaoSobeDeNivel() {
		EnumerationRiskEngine engine = engine();
		EnumerationRiskEngine.Assessment last = null;
		for (int i = 0; i < 200; i++) {
			String[] alvos = { "ana", "bruno", "carla" };
			last = engine.observe(new EnumerationRiskEngine.Observation("ip:2.2.2.2", "users.byHandle",
				EnumerationSurface.Kind.USERNAME, alvos[i % alvos.length], true, 200));
		}
		assertThat(last.level()).isEqualTo(EnumerationRiskEngine.Level.NONE);
		assertThat(last.distinctIdentifiers()).isEqualTo(3);
	}

	/** Várias abas abertas: o mesmo usuário logado bate nas rotas quentes da tela o tempo todo. */
	@Test
	void rotasQuentesDaTelaNaoContamComoEnumeracao() {
		EnumerationRiskEngine engine = engine();
		EnumerationRiskEngine.Assessment last = null;
		for (int i = 0; i < 300; i++) {
			last = engine.observe(new EnumerationRiskEngine.Observation("u:7", "conversations.stream",
				EnumerationSurface.Kind.SHARED, null, true, 200));
		}
		assertThat(last.level()).isEqualTo(EnumerationRiskEngine.Level.NONE);
	}

	/** Identidade logada tem balde próprio: o vizinho de NAT não pode contaminar a sessão. */
	@Test
	void identidadeAutenticadaTemBaldeSeparadoDoIp() {
		EnumerationRiskEngine engine = engine();
		for (int i = 1; i <= 30; i++) {
			engine.observe(new EnumerationRiskEngine.Observation("ip:3.3.3.3", "users.byHandle",
				EnumerationSurface.Kind.USERNAME, "spam" + i, false, 404));
		}
		EnumerationRiskEngine.Assessment limpo = engine.observe(
			new EnumerationRiskEngine.Observation("u:legitimo", "users.byHandle", EnumerationSurface.Kind.USERNAME,
				"amigo", true, 200));
		assertThat(limpo.level()).isEqualTo(EnumerationRiskEngine.Level.NONE);
	}

	@Test
	void sequenciaSoContaQuandoOsNumerosAndamDeUmEmUm() {
		assertThat(EnumerationRiskEngine.isSequential("user12", "user13")).isTrue();
		assertThat(EnumerationRiskEngine.isSequential("user12", "user14")).isTrue();
		assertThat(EnumerationRiskEngine.isSequential("user12", "user99")).isFalse();
		assertThat(EnumerationRiskEngine.isSequential("ana", "bruno")).isFalse();
		assertThat(EnumerationRiskEngine.isSequential("user12", "user12")).isFalse();
	}

	/** A janela escoa: o que ficou para trás de janela não conta. */
	@Test
	void aJanelaEscoaComOTempo() {
		java.time.Clock mutable = Clock.fixed(clock.instant(), ZoneOffset.UTC);
		EnumerationRiskEngine engine = new EnumerationRiskEngine(mutable, 1, 12, 25, 50, 4, 0.6, 8, 4);
		for (int i = 1; i <= 30; i++) {
			engine.observe(new EnumerationRiskEngine.Observation("ip:4.4.4.4", "users.byHandle",
				EnumerationSurface.Kind.USERNAME, "alvo" + i, false, 404));
		}
		assertThat(engine.peek("ip:4.4.4.4").level()).isGreaterThanOrEqualTo(EnumerationRiskEngine.Level.HIGH);

		mutable = Clock.fixed(clock.instant().plusSeconds(120), ZoneOffset.UTC);
		EnumerationRiskEngine depois = new EnumerationRiskEngine(mutable, 1, 12, 25, 50, 4, 0.6, 8, 4);
		assertThat(depois.peek("ip:4.4.4.4").level()).isEqualTo(EnumerationRiskEngine.Level.NONE);
	}
}
