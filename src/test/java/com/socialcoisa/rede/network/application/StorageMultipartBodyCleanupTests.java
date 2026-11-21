package com.socialcoisa.rede.network.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import com.socialcoisa.rede.network.domain.StorageObject;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * O temporário do corpo montado sai do disco quando a conclusão falha <b>depois</b> de montar.
 *
 * <p><b>O defeito.</b> A conclusão do multipart monta as partes num arquivo só, dentro do diretório
 * do upload, e só então chama o {@code finishUpload}. O {@code finally} que apagava esse temporário
 * cobria apenas a releitura do arquivo: quando a gravação recusava — cota do participante, falha de
 * I/O do catálogo —, a exceção subia por fora dele. O corpo de um objeto que nunca existiu ficava em
 * {@code <storageRoot>/multipart/<uploadId>/}, e o varredor de sobras não o recolhia porque a linha
 * do upload <b>existe</b> (o cliente ainda pode enviar outra parte). O upload aberto passava a ocupar
 * o dobro do próprio tamanho, sem que a cota, o catálogo ou o relatório soubessem.</p>
 *
 * <p><b>Por que aqui e não na rota.</b> Na rota esse estado é difícil de armar: a cota do objeto
 * (gravado + partes + corpo) e a do envio de cada parte cobram as mesmas partes, e é quase sempre o
 * <b>envio</b> que recusa primeiro. Aqui o {@code finishUpload} é substituído por um que recusa —
 * o contrato que a conclusão usa —, e o que se mede é só o disco.</p>
 *
 * <p>O discriminante é o diretório do upload depois da recusa: só {@code part-1}; nenhum
 * {@code .montado-*.tmp}.</p>
 */
class StorageMultipartBodyCleanupTests {

	private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");
	private static final String UPLOAD = "mpu_limpeza_1";
	private static final String DONO = "user_1";

	@TempDir
	Path root;

	/** O catálogo real, com a gravação recusando como a cota recusa. */
	static final class CatalogoQueRecusaNaGravacao extends StorageCatalogService {

		CatalogoQueRecusaNaGravacao(Path root) {
			super(new com.socialcoisa.rede.network.application.testsupport.CatalogoEmMemoria() {
			}, null, Clock.fixed(NOW, ZoneOffset.UTC), 10_000, root.toString(), "", null, null, null, 0, null);
		}

		@Override
		public StorageObject finishUpload(String objectId, InputStream body, String actorId,
				boolean cobrarPartesReservadas) {
			throw new ApiException(507, "STORAGE_OWNER_QUOTA_EXCEEDED",
				"O participante ja ocupa 12 bytes e esta gravacao soma 10, a cota desta instalacao e 20 bytes.");
		}
	}

	@SuppressWarnings("unchecked")
	@Test
	void conclusao_recusada_nao_deixa_o_corpo_montado_no_disco() throws Exception {
		JdbcTemplate jdbc = mock(JdbcTemplate.class);
		// O upload existente, com uma parte de 6 bytes.
		when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Long.class), any(Object[].class)))
			.thenReturn(0L);
		when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(inv -> {
			// `parts()` lê as partes do upload — a única consulta por lista desta cena.
			return List.of(new StorageMultipartService.Part(1, 6, "sha-da-parte"));
		});

		StorageCatalogService catalog = new CatalogoQueRecusaNaGravacao(root);
		StorageMultipartService multipart = new StorageMultipartService(jdbc, catalog,
			Clock.fixed(NOW, ZoneOffset.UTC), 24, 10_000, root.toString());

		// A parte de verdade no disco: é ela que a montagem copia.
		Path dir = root.resolve("multipart").resolve(UPLOAD);
		Files.createDirectories(dir);
		Files.write(dir.resolve("part-1"), "abcdef".getBytes(StandardCharsets.UTF_8));

		var upload = new StorageMultipartService.Upload(UPLOAD, "fotos", "gato.jpg", "image/jpeg", DONO,
			"s3c_1", NOW, NOW.plusSeconds(3600), StorageCatalogService.ClientHeaders.NONE);

		assertThatThrownBy(() -> multipart.complete(upload, List.of(1)))
			.isInstanceOf(ApiException.class)
			.hasMessageContaining("cota desta instalacao");

		try (Stream<Path> arquivos = Files.list(dir)) {
			List<String> nomes = arquivos.map(p -> p.getFileName().toString()).toList();
			assertThat(nomes).as("so a parte enviada pode sobrar no diretorio").containsExactly("part-1");
		}
	}

	/** A conclusão recusada antes da montagem (parte que não chegou) não cria temporário nenhum. */
	@Test
	void conclusao_recusada_antes_da_montagem_nao_deixa_temporario() throws Exception {
		JdbcTemplate jdbc = mock(JdbcTemplate.class);
		when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());

		StorageCatalogService catalog = new CatalogoQueRecusaNaGravacao(root);
		StorageMultipartService multipart = new StorageMultipartService(jdbc, catalog,
			Clock.fixed(NOW, ZoneOffset.UTC), 24, 10_000, root.toString());

		var upload = new StorageMultipartService.Upload(UPLOAD, "fotos", "gato.jpg", "image/jpeg", DONO,
			"s3c_1", NOW, NOW.plusSeconds(3600), StorageCatalogService.ClientHeaders.NONE);

		assertThatThrownBy(() -> multipart.complete(upload, List.of(1))).isInstanceOf(ApiException.class);
		// Nem o diretório: a montagem nunca começou.
		assertThat(Files.exists(root.resolve("multipart").resolve(UPLOAD))).isFalse();
	}
}