package com.socialcoisa.rede.network.adapters.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.socialcoisa.rede.network.application.port.out.StorageCatalogRepository;
import com.socialcoisa.rede.network.domain.StorageObject;

/**
 * A unicidade da chave no banco de verdade (V352), que é o que o upload depois de um prazo vencido
 * depende.
 *
 * <p>O dono da chave é o índice <b>condicional</b> {@code uq_network_storage_objects_current}, sobre
 * {@code (bucket, object_key, current_key)} — e {@code current_key} é NULL para a versão substituída.
 * Ou seja: a linha viva é única por chave, e uma linha aposentada não impede a chave de ter uma
 * nova. Esse é o contrato que o {@code beginUpload} usa; o teste em memória do serviço só imita.
 * Aqui é o H2 com as migrações reais que diz se o contrato existe.</p>
 */
@SpringBootTest
class StorageCatalogCurrentKeyPersistenceTests {

	private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private StorageCatalogRepository catalog;

	@BeforeEach
	void setUp() {
		jdbc.update("delete from network_storage_placements");
		jdbc.update("delete from network_storage_objects");
	}

	private void linha(String id, String bucket, String key, String state, Long bytes) {
		jdbc.update("""
			insert into network_storage_objects
				(id, bucket, object_key, content_type, bytes, sha256, state, created_by, created_at, updated_at)
			values (?, ?, ?, 'image/jpeg', ?, ?, ?, 'user_1', ?, ?)
			""", id, bucket, key, bytes, bytes == null ? null : "sha-" + id, state,
			java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW));
	}

	@Test
	void a_versao_aposentada_libera_a_chave_e_a_nova_fica_como_corrente() {
		linha("o1", "fotos", "gato.jpg", "STORED", 10L);

		// É o que o beginUpload faz com uma chave vencida: aposenta a corrente (superseded_by
		// preenchido, que zera o current_key) e insere a linha nova.
		assertThat(catalog.markSuperseded("fotos", "gato.jpg", "o2", NOW)).isTrue();
		linha("o2", "fotos", "gato.jpg", "PENDING", null);

		assertThat(catalog.find("fotos", "gato.jpg")).get()
			.extracting(StorageObject::id).isEqualTo("o2");
		assertThat(catalog.findById("o1")).get()
			.extracting(StorageObject::currentVersion).isEqualTo(false);
		assertThat(catalog.findById("o1")).get()
			.extracting(StorageObject::state).isEqualTo(com.socialcoisa.rede.network.domain.StorageObjectState.STORED);
	}

	@Test
	void a_lapide_que_continua_na_chave_impede_a_linha_nova() {
		linha("morta", "fotos", "gato.jpg", "DELETED", 10L);

		// A lápide não foi aposentada: ela continua sendo a corrente da chave, e a linha nova é
		// barrada pelo índice. É exatamente por isso que o beginUpload descarta a lápide antes. Se
		// este teste ficar vermelho, o insert passou a aceitar a segunda linha e a proteção saiu.
		assertThat(org.assertj.core.api.Assertions.catchThrowable(
			() -> linha("nova", "fotos", "gato.jpg", "PENDING", null)))
			.as("linha morta que ainda é a corrente não convive com linha nova")
			.isInstanceOf(org.springframework.dao.DataAccessException.class);

		assertThat(catalog.find("fotos", "gato.jpg")).get()
			.extracting(StorageObject::id).isEqualTo("morta");
	}

	@Test
	void a_lapide_descartada_libera_a_chave_para_uma_linha_nova() {
		linha("morta", "fotos", "gato.jpg", "DELETED", 10L);

		assertThat(catalog.discardPending("morta")).isTrue();
		linha("nova", "fotos", "gato.jpg", "PENDING", null);

		assertThat(catalog.findById("morta")).isEmpty();
		assertThat(catalog.find("fotos", "gato.jpg")).get()
			.extracting(StorageObject::id).isEqualTo("nova");
	}

	@Test
	void a_segunda_linha_viva_na_mesma_chave_e_recusada() {
		linha("o1", "fotos", "gato.jpg", "STORED", 10L);

		// A proteção do índice: sem aposentar a corrente, a chave só tem uma linha viva. Sem isto,
		// duas linhas correntes fariam o download mostrar um objeto e o listar outro.
		assertThat(org.assertj.core.api.Assertions.catchThrowable(
			() -> linha("o2", "fotos", "gato.jpg", "PENDING", null)))
			.as("o índice condicional tem de barrar a segunda corrente")
			.isInstanceOf(org.springframework.dao.DataAccessException.class);
	}
}