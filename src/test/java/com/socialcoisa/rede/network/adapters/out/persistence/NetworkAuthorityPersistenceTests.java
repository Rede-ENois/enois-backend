package com.socialcoisa.rede.network.adapters.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.socialcoisa.rede.network.application.port.out.NetworkAuthorityRepository;

/**
 * O compare-and-set do termo é o que torna "dois controladores com o mesmo termo" impossível POR
 * CONSTRUÇÃO (doc 06 §4, regra 4): a promoção é um {@code update ... where term = <lido>} e quem
 * perder atualiza zero linhas. Um {@code select} antes do {@code update} teria a janela em que duas
 * instâncias leem o mesmo termo e as duas se promovem — por isso o juiz é o banco, não a aplicação.
 */
@SpringBootTest
class NetworkAuthorityPersistenceTests {

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private NetworkAuthorityRepository authorities;

	@BeforeEach
	void setUp() {
		jdbc.update("delete from network_authorities");
		jdbc.update("delete from network_instance_readers");
		jdbc.update("delete from network_state");
		// A linha global existe SEMPRE em produção (seed da V288). O delete acima apaga porque
		// outros testes a deixam em estados arbitrários; sem recriar, o CAS da promoção atualizaria
		// zero linhas e falharia por ausência do invariante, não por corrida — o que este teste mede.
		jdbc.update("insert into network_state (id, mode, reason, since, term) values ('global', 'NORMAL', ?, ?, 0)",
			"estado de teste", java.sql.Timestamp.from(Instant.now()));
	}

	@Test
	void promoteSoGravaComOTermoEsperadoEPerdedoraAtualizaZeroLinhas() {
		// O termo 3 já vigente: a corrida é entre duas promoções que leram 3, não sobre a linha vazia.
		jdbc.update("update network_state set term = 3 where id = 'global'");

		// Duas promoções leram o MESMO termo 3 e disputam a mesma linha.
		boolean first = authorities.promote("cp-1", 3, 4, Instant.now());
		boolean second = authorities.promote("cp-2", 3, 4, Instant.now());

		assertThat(first).isTrue();
		assertThat(second).isFalse();
		var state = authorities.current().orElseThrow();
		assertThat(state.term()).isEqualTo(4);
		assertThat(state.authorityId()).isEqualTo("cp-1");
		assertThat(state.promotedAt()).isNotNull();
	}

	@Test
	void promoteSemLinhaGlobalNaoGravaERespondeFalso() {
		// Ausência deliberada: o banco predating o seed ou alguém apagou a linha. O CAS responde
		// falso (e não crasha) — o serviço sobe o NETWORK_AUTHORITY_PROMOTION_LOST, nunca grava termo.
		jdbc.update("delete from network_state");
		assertThat(authorities.promote("cp-1", 0, 1, Instant.now())).isFalse();
		assertThat(authorities.current()).isEmpty();
	}

	@Test
	void eleitoradoEVivacidadeVemDeTabelasDiferentesESeEncontramNoAlive() {
		Instant now = Instant.now();
		jdbc.update("insert into network_authorities (instance_id, declared_at, declared_by) values ('cp-1', ?, 'a')",
			java.sql.Timestamp.from(now));
		jdbc.update("insert into network_authorities (instance_id, declared_at, declared_by) values ('cp-2', ?, 'a')",
			java.sql.Timestamp.from(now));
		jdbc.update("""
				insert into network_instance_readers (instance_id, started_at, last_seen_at, last_seq)
				values ('cp-1', ?, ?, 0)
				""", java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));
		// cp-2 declarada mas SEM sinal recente.

		assertThat(authorities.aliveSince(now.minusSeconds(30))).containsExactly("cp-1");
		// Sinal velho não conta como vivo.
		assertThat(authorities.aliveSince(now.plusSeconds(30))).isEmpty();
		assertThat(authorities.declared()).containsExactly("cp-1", "cp-2");
	}

	@Test
	void declareRepetidoNaoReescreveADataDeEntrada() {
		Instant first = Instant.parse("2026-11-01T10:00:00Z");
		Instant second = Instant.parse("2026-11-10T10:00:00Z");
		jdbc.update("insert into network_authorities (instance_id, declared_at, declared_by) values ('cp-1', ?, 'a')",
			java.sql.Timestamp.from(first));

		authorities.declare("cp-1", "b", second);

		Instant declaredAt = jdbc.queryForObject(
			"select declared_at from network_authorities where instance_id = 'cp-1'", Instant.class);
		assertThat(declaredAt).isEqualTo(first);
		assertThat(authorities.revoke("cp-1")).isTrue();
		assertThat(authorities.revoke("cp-1")).isFalse();
	}
}