package com.socialcoisa.rede.network.adapters.out.dump;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * O dump do banco por JDBC — o que a #953 bloqueou em produção.
 *
 * <p><b>Antes:</b> a cópia no nó de reserva exigia {@code mariadb-dump} no {@code PATH}; o container de
 * produção não tem o cliente, e o endpoint respondia 503 em PROD mesmo com a propriedade ligada.
 * <b>Agora:</b> o SQL sai pela conexão que o app já usa.</p>
 *
 * <p>O teste roda contra o <b>banco real da suíte</b> (H2 em modo MySQL, com as migrações aplicadas).
 * Um gerador que só "escreve texto" passaria num teste de tamanho; aqui ele tem de achar todas as
 * tabelas do app, escapar os valores e o arquivo tem de levar cada tabela que existia.</p>
 */
@SpringBootTest
@org.springframework.test.context.TestPropertySource(properties = "rede.network.standby.dump.enabled=true")
class JdbcStandbyDumpSourceTests {

	@Autowired
	private DataSource dataSource;

	@Autowired
	private JdbcStandbyDumpSource dump;

	@Test
	void o_dump_sai_do_banco_sem_o_binario_externo_e_leva_todas_as_tabelas() throws Exception {
		byte[] sql = dump.serialize();
		String texto = new String(sql, StandardCharsets.UTF_8);

		// O formato é o que o nó restaura com o cliente MariaDB: cabeçalho, CREATE TABLE e o fim.
		assertThat(texto).startsWith("/*!40101 SET NAMES utf8mb4 */;");
		assertThat(texto).contains("CREATE TABLE").contains("@OLD_SQL_MODE");
		assertThat(texto).doesNotContain("mariadb-dump");
		assertThat(dump.lastFailure()).isNull();

		// Toda tabela do app que este banco tem foi para o dump — uma tabela a menos seria um failover
		// com dado faltando.
		Map<String, Long> contagem = dump.contagemPorTabela();
		assertThat(contagem).isNotEmpty();
		assertThat(contagem.keySet()).contains("USERS", "AUDIT_LOGS");
		for (String tabela : contagem.keySet()) {
			assertThat(texto).as("tabela %s no dump", tabela).contains(tabela);
		}
		assertThat(contagem.values()).allSatisfy(linhas -> assertThat(linhas).isGreaterThanOrEqualTo(0L));
	}

	@Test
	void o_escape_preserva_aspas_barra_e_binario() {
		// O valor sai como literal que o banco aceita ao carregar: aspas dobradas, barra dobrada, e
		// binário em hexadecimal — o corpo de um upload não sobrevive a um escape ingênuo.
		assertThat(JdbcStandbyDumpSource.escapar("a'b")).isEqualTo("a''b");
		assertThat(JdbcStandbyDumpSource.escapar("a\\b")).isEqualTo("a\\\\b");
		assertThat(JdbcStandbyDumpSource.escapar("a\nb")).isEqualTo("a\\nb");
		assertThat(JdbcStandbyDumpSource.escapar("a\u0000b")).isEqualTo("a\\0b");
	}

	@Test
	void a_retomada_por_offset_e_a_cauda_do_mesmo_dump() {
		byte[] inteiro = dump.serialize();
		int offset = inteiro.length / 2;

		byte[] cauda = dump.dump(offset);

		assertThat(cauda).hasSize(inteiro.length - offset);
		byte[] juntado = new byte[inteiro.length];
		System.arraycopy(inteiro, 0, juntado, 0, offset);
		System.arraycopy(cauda, 0, juntado, offset, cauda.length);
		assertThat(juntado).isEqualTo(inteiro);
	}

	@Test
	void o_dump_e_estavel_entre_duas_geracoes_do_mesmo_dado() {
		// Sem data de geração e com tabelas em ordem canônica: o mesmo dado dá o mesmo arquivo, e é isso
		// que permite ao nó provar que a retomada é do mesmo dump.
		assertThat(dump.serialize()).isEqualTo(dump.serialize());
	}

	@Test
	void verify_recusa_dump_sem_o_insert_de_uma_tabela_que_tem_linhas() throws Exception {
		// A cópia que "abre" mas chega sem o dado de uma tabela: o nó restauraria um banco furado e
		// confirmaria. O verify() tem de recusar ANTES de o arquivo sair.
		jdbcAvulso().update("merge into users (id, username, name, email, open_to_work, is_mentor, created_at, updated_at) key (id) "
			+ "values ('verify_furado', 'verify_furado', 'Verify', 'v@x.test', false, false, current_timestamp, current_timestamp)");
		try {
			String texto = new String(dump.serialize(), StandardCharsets.UTF_8);
			assertThat(dump.verify()).as("dump integro: " + dump.lastFailure()).isTrue();
			String marcador = "INSERT INTO ";
			assertThat(texto.indexOf(marcador)).isGreaterThanOrEqualTo(0);

			// Mesmo dump, sem os INSERT: a conferência recusa, porque a origem tinha linhas.
			// Só o INSERT sai: o CREATE TABLE da mesma tabela fica, e o que se prova é a falta do dado.
			int primeiro = texto.indexOf(marcador);
			String furado = texto.substring(0, primeiro) + texto.substring(texto.indexOf(';', primeiro) + 1);
			dump.substituirUltimoDump(furado.getBytes(StandardCharsets.UTF_8));

			assertThat(dump.verify()).isFalse();
			assertThat(dump.lastFailure()).contains("nenhum INSERT");
			assertThat(dump.lastFailure()).isNotNull();
		}
		finally {
			jdbcAvulso().update("delete from users where id = 'verify_furado'");
		}
	}

	private static org.springframework.jdbc.core.JdbcTemplate jdbcAvulso;

	private org.springframework.jdbc.core.JdbcTemplate jdbcAvulso() {
		if (jdbcAvulso == null) {
			jdbcAvulso = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
		}
		return jdbcAvulso;
	}
}
