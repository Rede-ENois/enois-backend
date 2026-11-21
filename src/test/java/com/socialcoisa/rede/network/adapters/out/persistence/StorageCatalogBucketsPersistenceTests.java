package com.socialcoisa.rede.network.adapters.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.socialcoisa.rede.network.application.port.out.StorageCatalogRepository;

/**
 * O resumo por bucket do painel (§17) contra o SQL de verdade, no H2.
 *
 * <p>Os testes do serviço usam mock e provam a <b>ligação</b> com o repositório, não a consulta.
 * Aqui o que está em teste é o {@code group by} e o que ele deixa de fora — e as duas coisas que
 * erram com facilidade: contar lápide como bucket (um bucket que o operador não tem) e somar
 * bytes de objeto que ainda não ocupa espaço.</p>
 */
@SpringBootTest
class StorageCatalogBucketsPersistenceTests {

	private static final Instant NOW = Instant.parse("2026-11-17T12:00:00Z");

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private StorageCatalogRepository catalog;

	@BeforeEach
	void setUp() {
		jdbc.update("delete from network_storage_placements");
		jdbc.update("delete from network_storage_objects");
	}

	private void objeto(String id, String bucket, String key, Long bytes, String state) {
		jdbc.update("""
			insert into network_storage_objects
				(id, bucket, object_key, content_type, bytes, sha256, state, created_by, created_at, updated_at)
			values (?, ?, ?, 'image/jpeg', ?, ?, ?, 'user_1', ?, ?)
			""", id, bucket, key, bytes, bytes == null ? null : "sha-" + id, state,
			java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW));
	}

	@Test
	void agrupaPorBucketComBytesSomadosEOrdemEstavel() {
		objeto("o1", "fotos", "a.jpg", 100L, "STORED");
		objeto("o2", "fotos", "b.jpg", 250L, "STORED");
		objeto("o3", "midia", "c.mp4", 4000L, "STORED");

		Map<String, long[]> porBucket = catalog.summarizeBuckets();

		// O tamanho do mapa É a quantidade de buckets do painel.
		assertThat(porBucket).hasSize(2);
		assertThat(porBucket.get("fotos")).containsExactly(2L, 350L);
		assertThat(porBucket.get("midia")).containsExactly(1L, 4000L);
		// Ordem por nome: o painel não reordena, e ordem instável faria a tela pular entre refreshs.
		assertThat(porBucket.keySet()).containsExactly("fotos", "midia");
	}

	/**
	 * Lápide não é conteúdo: o bucket que só tem objeto removido não aparece. Se aparecesse, o
	 * operador veria um bucket "existindo" com um objeto fantasma.
	 */
	@Test
	void bucket_que_so_tem_lapide_nao_aparece() {
		objeto("o1", "fotos", "a.jpg", 100L, "STORED");
		objeto("o2", "apagados", "velho.jpg", 500L, "DELETED");

		assertThat(catalog.summarizeBuckets().keySet()).containsExactly("fotos");
	}

	/**
	 * Objeto PENDING existe no bucket mas ainda não tem bytes medidos: conta na quantidade e soma
	 * zero. Somar um tamanho que não foi medido inflaria o "espaço utilizado" do painel.
	 */
	@Test
	void objeto_pendente_conta_na_quantidade_e_soma_zero() {
		objeto("o1", "fotos", "a.jpg", 100L, "STORED");
		objeto("o2", "fotos", "novo.jpg", null, "PENDING");

		assertThat(catalog.summarizeBuckets().get("fotos")).containsExactly(2L, 100L);
	}

	/**
	 * Versão <b>substituída</b> não ocupa o "espaço utilizado" do bucket.
	 *
	 * <p><b>Antes:</b> o {@code group by} somava {@code bytes} de toda linha com
	 * {@code state <> 'DELETED'} — inclusive a versão que já foi substituída por outra. O
	 * {@code usedBytes} do mesmo painel conta só cópia {@code OK} (a versão corrente), então os dois
	 * números do resumo discordavam: o bucket "ocupava" o dobro do que o topo dizia estar em uso, e
	 * o operador não sabia qual acreditar.</p>
	 *
	 * <p><b>Agora:</b> só a versão <b>corrente</b> ({@code superseded_by is null}) conta. A antiga
	 * continua no histórico e continua ocupando <b>disco</b> — o que ela não é é "o que este bucket
	 * guarda hoje".</p>
	 */
	@Test
	void versao_substituida_nao_soma_no_uso_do_bucket() {
		objeto("o1", "fotos", "a.jpg", 100L, "STORED");
		objeto("o2", "fotos", "b.jpg", 250L, "STORED");
		jdbc.update("update network_storage_objects set superseded_by = 'o2' where id = 'o1'");

		assertThat(catalog.summarizeBuckets().get("fotos")).containsExactly(2L, 250L);
	}

	/** Catálogo vazio não inventa bucket: mapa vazio, e o painel mostra zero. */
	@Test
	void catalogo_vazio_nao_inventa_bucket() {
		assertThat(catalog.summarizeBuckets()).isEmpty();
	}

	/**
	 * O mesmo bucket em grafias diferentes é UM bucket: ele é a chave, não o texto livre de uma
	 * tela. "Fotos" e "fotos" são dois buckets de verdade (S3 é case-sensitive) — o teste fixa isso
	 * para ninguém "normalizar" o bucket no caminho e juntar dois espaços de nomes.
	 */
	@Test
	void grafia_do_bucket_e_dois_buckets_de_verdade() {
		objeto("o1", "fotos", "a.jpg", 100L, "STORED");
		objeto("o2", "Fotos", "b.jpg", 100L, "STORED");

		assertThat(catalog.summarizeBuckets()).hasSize(2);
	}

}