package com.socialcoisa.rede.notifications.adapters.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import org.junit.jupiter.api.Tag;
import jakarta.persistence.Column;

/**
 * `read` e palavra reservada no MariaDB/MySQL.
 *
 * Sem os backticks o Hibernate gera `update notifications set ..., read=?, ...` e o banco recusa
 * com erro 1064 — o que derrubava qualquer insert/update de notificacao (o 500 ao comentar um post
 * e ao "marcar tudo como lido" em producao, 2026-10-23).
 *
 * O H2 dos testes aceita `read` sem escape, entao um teste de repositorio NAO pega esse defeito;
 * este aqui olha o mapeamento, que e o que vai para o SQL.
 */

@Tag("critico")
class NotificationColumnQuotingTests {

	@Test
	void colunaReadVaiEscapadaNoMapeamento() throws Exception {
		var campo = NotificationJpaEntity.class.getDeclaredField("read");
		var coluna = campo.getAnnotation(Column.class);

		assertThat(coluna).isNotNull();
		assertThat(coluna.name()).isEqualTo("is_read");
	}
}
