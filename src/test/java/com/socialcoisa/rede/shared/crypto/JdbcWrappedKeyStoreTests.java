package com.socialcoisa.rede.shared.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A tabela de chaves guarda só o embrulho do cofre e precisa saber qual chave cifra registros
 * novos.
 *
 * <p>Isolamento: a base H2 é compartilhada entre classes, então os fixtures usam ids próprios e
 * datas futuras dentro da faixa aceita pelo MariaDB (TIMESTAMP vai até 2038). Nenhuma asserção
 * depende de a tabela estar vazia.</p>
 */
@SpringBootTest
class JdbcWrappedKeyStoreTests {

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void guardaEObterOEmbrulho() {
		JdbcWrappedKeyStore store = new JdbcWrappedKeyStore(jdbc);
		String kid = "kid-teste-guarda";

		store.save(kid, "ciphertext-do-cofre");

		assertThat(store.wrappedByKid(kid)).contains("ciphertext-do-cofre");
		assertThat(store.wrappedByKid("kid-inexistente")).isEmpty();
	}

	@Test
	void chaveAposentadaSaiDaAtivaMasContinuaAbrindoORegistroAntigo() {
		JdbcWrappedKeyStore store = new JdbcWrappedKeyStore(jdbc);
		// Datas no futuro (e dentro da faixa do TIMESTAMP) garantem que a ativa e uma destas.
		jdbc.update("INSERT INTO data_keys (kid, wrapped_key, created_at) VALUES (?, ?, ?)",
			"kid-teste-velha", "embrulho-velho", java.sql.Timestamp.valueOf("2037-06-01 00:00:00"));
		jdbc.update("INSERT INTO data_keys (kid, wrapped_key, created_at) VALUES (?, ?, ?)",
			"kid-teste-nova", "embrulho-novo", java.sql.Timestamp.valueOf("2037-06-02 00:00:00"));

		assertThat(store.activeKid()).isEqualTo("kid-teste-nova");

		store.retire("kid-teste-nova");

		assertThat(store.activeKid()).isEqualTo("kid-teste-velha");
		assertThat(store.wrappedByKid("kid-teste-nova")).contains("embrulho-novo");
	}

	@Test
	void embrulhoVazioNaoEntra() {
		JdbcWrappedKeyStore store = new JdbcWrappedKeyStore(jdbc);

		org.assertj.core.api.Assertions.assertThatThrownBy(() -> store.save("kid-vazio", " "))
			.isInstanceOfSatisfying(CryptoException.class,
				failure -> assertThat(failure.code()).isEqualTo("KEYSTORE_INVALID"));
	}
}
