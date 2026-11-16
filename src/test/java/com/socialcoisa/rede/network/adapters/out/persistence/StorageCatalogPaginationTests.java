package com.socialcoisa.rede.network.adapters.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import com.socialcoisa.rede.network.application.StoragePlacementService;
import com.socialcoisa.rede.network.application.StorageScrubber;
import com.socialcoisa.rede.network.application.port.out.NetworkNodeRepository;
import com.socialcoisa.rede.network.application.port.out.StorageCatalogRepository;
import com.socialcoisa.rede.network.domain.StorageObject;
import com.socialcoisa.rede.shared.audit.AuditTrail;

class StorageCatalogPaginationTests {

	private static final Instant AGORA = Instant.parse("2026-11-15T12:00:00Z");
	private Connection conexao;
	private AnnotationConfigApplicationContext contexto;
	private StorageCatalogRepository catalogo;
	private StorageScrubber scrubber;

	@BeforeEach
	void preparar() throws Exception {
		var fonte = new DriverManagerDataSource("jdbc:h2:mem:paginacao_" + UUID.randomUUID()
			+ ";MODE=MySQL", "sa", "");
		// A conexão mantém apenas a base deste cenário viva; fechá-la descarta tudo, sem cache Spring.
		conexao = fonte.getConnection();
		new ResourceDatabasePopulator(
			new ClassPathResource("db/migration/V319__network_storage_objects.sql"),
			new ClassPathResource("db/migration/V320__network_storage_placements.sql")).execute(fonte);
		catalogo = new StorageCatalogPersistenceAdapter(new JdbcTemplate(fonte));
		contexto = new AnnotationConfigApplicationContext();
		contexto.registerBean(StorageCatalogRepository.class, () -> catalogo);
		contexto.registerBean(NetworkNodeRepository.class, () -> mock(NetworkNodeRepository.class));
		contexto.registerBean(AuditTrail.class, () -> mock(AuditTrail.class));
		contexto.registerBean(Clock.class, () -> Clock.fixed(AGORA, ZoneOffset.UTC));
		contexto.registerBean(com.socialcoisa.rede.network.application.StorageCatalogService.class, () -> mock(com.socialcoisa.rede.network.application.StorageCatalogService.class));
		contexto.register(StoragePlacementService.class, StorageScrubber.class);
		contexto.refresh();
		scrubber = contexto.getBean(StorageScrubber.class);
	}

	@AfterEach
	void fechar() throws Exception {
		try {
			if (contexto != null) {
				contexto.close();
			}
		} finally {
			if (conexao != null) {
				conexao.close();
			}
		}
	}

	@Test
	void varredura_global_visita_cada_identidade_uma_vez_entre_paginas_e_buckets() {
		List<String> esperados = criarCatalogoMultipagina();

		var relatorio = scrubber.scan(null, 600);

		assertThat(relatorio.objectsScanned()).isEqualTo(esperados.size());
		assertThat(relatorio.underReplicated()).extracting(StorageScrubber.Report.UnderReplicated::objectId)
			.containsExactlyElementsOf(esperados);
	}

	@ParameterizedTest
	@ValueSource(ints = {0, 200, 201, 402, 403})
	void varredura_global_respeita_o_teto_sem_gastar_cota_com_repeticoes(int teto) {
		List<String> esperados = criarCatalogoMultipagina();
		int quantidade = Math.min(esperados.size(), Math.max(1, teto));

		var relatorio = scrubber.scan(null, teto);

		assertThat(relatorio.objectsScanned()).isEqualTo(quantidade);
		assertThat(relatorio.underReplicated()).extracting(StorageScrubber.Report.UnderReplicated::objectId)
			.containsExactlyElementsOf(esperados.subList(0, quantidade));
	}

	@Test
	void varredura_por_bucket_preserva_escopo_e_paginacao_exclusiva() {
		List<String> esperados = criarCatalogoMultipagina();

		var relatorio = scrubber.scan("bucket-b", 600);

		assertThat(relatorio.objectsScanned()).isEqualTo(201);
		assertThat(relatorio.underReplicated()).extracting(StorageScrubber.Report.UnderReplicated::objectId)
			.containsExactlyElementsOf(esperados.subList(200, 401));
	}

	@Test
	void varredura_preserva_vazio_estados_e_repeticao_sem_mutacao() {
		assertThat(scrubber.scan(null, 600).objectsScanned()).isZero();
		String pendente = UUID.randomUUID().toString();
		catalogo.createPending(pendente, "bucket-a", "a", "application/octet-stream", "fixture_paginacao", AGORA);
		String armazenado = criarArmazenado("bucket-a", "b");
		String removido = criarArmazenado("bucket-a", "c");
		assertThat(catalogo.markDeleted(removido, AGORA)).isTrue();

		var relatorio = scrubber.scan(null, 600);

		assertThat(relatorio.objectsScanned()).isEqualTo(2);
		assertThat(relatorio.replicasExpected()).isEqualTo(2);
		assertThat(relatorio.underReplicated()).containsExactly(
			new StorageScrubber.Report.UnderReplicated(armazenado, 0, 2, List.of()));
		assertThat(scrubber.scan(null, 600)).isEqualTo(relatorio);
		assertThat(catalogo.findById(pendente).orElseThrow().servable()).isFalse();
		assertThat(catalogo.markDeleted(removido, AGORA)).isFalse();
	}

	@ParameterizedTest
	@ValueSource(ints = {1, 2, 200})
	void paginas_globais_terminam_sem_repetir_ou_omitir_chaves_iguais(int limite) {
		String pendente = UUID.randomUUID().toString();
		catalogo.createPending(pendente, "bucket-a", "y", "application/octet-stream", "fixture_paginacao", AGORA);
		List<String> esperados = List.of(pendente, criarArmazenado("bucket-a", "z"),
			criarArmazenado("bucket-b", "a"), criarArmazenado("bucket-b", "z"),
			criarArmazenado("bucket-c", "z"));
		String removido = criarArmazenado("bucket-b", "m");
		assertThat(catalogo.markDeleted(removido, AGORA)).isTrue();
		List<String> visitados = new ArrayList<>();
		StorageCatalogRepository.Cursor cursor = null;
		boolean terminou = false;
		// Até uma linha por página deve acabar em N páginas não vazias e uma consulta vazia.
		for (int pagina = 0; pagina <= esperados.size(); pagina++) {
			List<StorageObject> objetos = catalogo.listAll(cursor, limite);
			assertThat(objetos).hasSizeLessThanOrEqualTo(limite);
			if (objetos.isEmpty()) {
				terminou = true;
				break;
			}
			visitados.addAll(objetos.stream().map(StorageObject::id).toList());
			StorageObject ultimo = objetos.getLast();
			cursor = new StorageCatalogRepository.Cursor(ultimo.bucket(), ultimo.key());
		}
		assertThat(terminou).as("paginação deve terminar em até N + 1 consultas").isTrue();
		assertThat(visitados).containsExactlyElementsOf(esperados);
	}

	private List<String> criarCatalogoMultipagina() {
		List<String> ids = new ArrayList<>();
		for (int i = 0; i < 200; i++) {
			ids.add(criarArmazenado("bucket-a", "z%03d".formatted(i)));
		}
		for (int i = 0; i < 200; i++) {
			ids.add(criarArmazenado("bucket-b", "a%03d".formatted(i)));
		}
		ids.add(criarArmazenado("bucket-b", "z199"));
		ids.add(criarArmazenado("bucket-c", "z199"));
		return ids;
	}

	private String criarArmazenado(String bucket, String chave) {
		String id = UUID.randomUUID().toString();
		catalogo.createPending(id, bucket, chave, "application/octet-stream", "fixture_paginacao", AGORA);
		assertThat(catalogo.markStored(id, 1, "a".repeat(64), AGORA)).isTrue();
		return id;
	}
}
