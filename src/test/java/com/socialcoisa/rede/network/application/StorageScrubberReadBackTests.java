package com.socialcoisa.rede.network.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.socialcoisa.rede.network.application.port.out.StorageCatalogRepository;
import com.socialcoisa.rede.network.domain.NetworkNode;
import com.socialcoisa.rede.network.domain.NodeProfile;
import com.socialcoisa.rede.network.domain.NodeStatus;
import com.socialcoisa.rede.network.domain.StorageObject;
import com.socialcoisa.rede.network.domain.StorageObjectState;
import com.socialcoisa.rede.network.domain.StoragePlacement;
import com.socialcoisa.rede.shared.audit.AuditTrail;

/**
 * A varredura que relê o corpo: o catálogo promete durabilidade, e a única forma de saber se a
 * promessa vale é ler o arquivo de novo.
 *
 * <p>O que estes testes fixam, e por quê:</p>
 * <ul>
 * <li><b>corpo corrompido no disco é divergência</b> — sem a releitura, contar linhas {@code OK}
 * daria "tudo certo" para um arquivo truncado. É o cenário que só a leitura detecta;</li>
 * <li><b>bytes trocados são divergência</b> mesmo com o mesmo tamanho: comparar só o tamanho
 * deixaria passar a troca de conteúdo — o caso mais provável de corrupção silenciosa;</li>
 * <li><b>corpo ausente é divergência</b>, não "sem dado": o catálogo diz que a cópia existe;</li>
 * <li><b>sem releitura, nada é relatado</b> — o padrão não custa disco de quem contribui, e a
 * varredura continua sendo a contagem de antes.</li>
 * </ul>
 */
class StorageScrubberReadBackTests {

	private static final Instant NOW = Instant.parse("2026-11-15T18:00:00Z");

	@TempDir
	Path root;

	private InMemoryCatalog catalog;
	private AuditTrail audit;
	private StorageCatalogService service;
	private StorageScrubber scrubber;

	@BeforeEach
	void setUp() {
		catalog = new InMemoryCatalog();
		audit = mock(AuditTrail.class);
		service = new StorageCatalogService(catalog, audit, Clock.fixed(NOW, ZoneOffset.UTC), 10_000, root.toString(),
			stubPlacement());
		scrubber = new StorageScrubber(catalog, stubPlacement(), audit, Clock.fixed(NOW, ZoneOffset.UTC), service);
	}

	/** A colocação não participa da releitura: o alvo de cópias é 2 e nenhum nó é candidato. */
	private static StoragePlacementService stubPlacement() {
		StoragePlacementService placement = mock(StoragePlacementService.class);
		org.mockito.Mockito.when(placement.targetReplicas()).thenReturn(2);
		return placement;
	}

	private static NetworkNode node() {
		return new NetworkNode("nd_ctrl", "vps-casa", "user_1", NodeStatus.ACTIVE, Set.of(NodeProfile.NODE_STORAGE),
			"sa-east-1", "BR", "1.0.0", null, 2, 4096, 102400, 100, null, null, null, null, null, 0, null, null);
	}

	private static NetworkNode outraCopia() {
		return new NetworkNode("nd_replica", "vps-b", "user_2", NodeStatus.ACTIVE, Set.of(NodeProfile.NODE_STORAGE),
			"sa-east-1", "BR", "1.0.0", null, 2, 4096, 102400, 100, null, null, null, null, null, 0, null, null);
	}

	private static byte[] body(String text) {
		return text.getBytes(StandardCharsets.UTF_8);
	}

	/**
	 * Objeto gravado pelo caminho real: o corpo fica no diretório deste Control Plane. O retorno é
	 * relido do catálogo — {@code beginUpload} devolve o objeto {@code PENDING}, e o sha-256 só
	 * existe depois de {@code store} gravar o medido.
	 */
	private StorageObject gravado(byte[] content) {
		StorageObject object = service.beginUpload("user_1", "fotos", "gato-" + content.length + ".jpg", "image/jpeg");
		service.store(node(), object.id(), new ByteArrayInputStream(content));
		return catalog.findById(object.id()).orElseThrow();
	}

	@Test
	void corpo_intacto_no_disco_nao_e_reportado_como_divergente() throws Exception {
		gravado(body("conteudo do arquivo"));

		StorageScrubber.Report report = scrubber.scan("fotos", 10, true);

		assertThat(report.bodiesRelidos()).isEqualTo(1);
		assertThat(report.divergent()).isEmpty();
	}

	@Test
	void corpo_trocado_no_disco_e_reportado_com_o_digest_que_ele_tem_agora() throws Exception {
		StorageObject object = gravado(body("conteudo do arquivo"));
		// O mesmo tamanho, conteúdo diferente: é o caso em que comparar comprimento passaria.
		Files.write(service.bodyPath(object.id()), body("CONTEUDO DO ARQUIVO"));

		StorageScrubber.Report report = scrubber.scan("fotos", 10, true);

		assertThat(report.divergent()).hasSize(1);
		StorageScrubber.Report.Divergent divergente = report.divergent().get(0);
		assertThat(divergente.objectId()).isEqualTo(object.id());
		assertThat(divergente.expectedSha256()).isEqualTo(object.sha256());
		assertThat(divergente.actualSha256()).isNotEqualTo(object.sha256()).isNotBlank();
		verify(audit).record(eq("system"), eq("STORAGE_SCRUB_DIVERGENT"), anyString(), anyString());
	}

	@Test
	void corpo_ausente_e_divergencia_nao_sem_dado() throws Exception {
		StorageObject object = gravado(body("conteudo do arquivo"));
		Files.delete(service.bodyPath(object.id()));

		StorageScrubber.Report report = scrubber.scan("fotos", 10, true);

		assertThat(report.divergent()).hasSize(1);
		// Digest vazio é "não consegui ler" — nunca um digest calculado sobre nada.
		assertThat(report.divergent().get(0).actualSha256()).isEmpty();
	}

	@Test
	void sem_releitura_a_varredura_nao_le_o_disco_e_nao_relata_divergencia() throws Exception {
		StorageObject object = gravado(body("conteudo do arquivo"));
		Files.write(service.bodyPath(object.id()), body("CONTEUDO DO ARQUIVO"));

		StorageScrubber.Report report = scrubber.scan("fotos", 10);

		assertThat(report.bodiesRelidos()).isZero();
		assertThat(report.divergent()).isEmpty();
	}

	/** Catálogo em memória: o suficiente para a varredura e para o upload do caminho real. */
	private static final class InMemoryCatalog implements StorageCatalogRepository {

		private final List<StorageObject> objects = new ArrayList<>();
		private final List<StoragePlacement> placements = new ArrayList<>();

		@Override
		public Optional<StorageObject> find(String bucket, String key) {
			return objects.stream().filter(o -> o.bucket().equals(bucket) && o.key().equals(key)).findFirst();
		}

		@Override
		public Optional<StorageObject> findById(String id) {
			return objects.stream().filter(o -> o.id().equals(id)).findFirst();
		}

		@Override
		public Optional<StorageObject> findByDigest(String sha256) {
			return objects.stream().filter(o -> sha256.equals(o.sha256())).findFirst();
		}

		@Override
		public StorageObject createPending(String id, String bucket, String key, String contentType, String createdBy,
				Instant now) {
			Optional<StorageObject> existing = find(bucket, key);
			if (existing.isPresent()) {
				return existing.get();
			}
			StorageObject object = new StorageObject(id, bucket, key, contentType, null, null,
				StorageObjectState.PENDING, createdBy, now, now, null);
			objects.add(object);
			return object;
		}

		@Override
		public boolean markStored(String id, long bytes, String sha256, Instant now) {
			for (int i = 0; i < objects.size(); i++) {
				StorageObject o = objects.get(i);
				if (o.id().equals(id) && o.state() == StorageObjectState.PENDING) {
					objects.set(i, new StorageObject(o.id(), o.bucket(), o.key(), o.contentType(), bytes, sha256,
						StorageObjectState.STORED, o.createdBy(), o.createdAt(), now, null));
					return true;
				}
			}
			return false;
		}

		@Override
		public boolean markDeleted(String id, Instant now) {
			return false;
		}

		@Override
		public List<StorageObject> list(String bucket, String prefix, String afterKey, int limit) {
			return objects.stream()
				.filter(o -> o.bucket().equals(bucket) && o.state() == StorageObjectState.STORED)
				.filter(o -> o.key().startsWith(prefix == null ? "" : prefix))
				.filter(o -> afterKey == null || o.key().compareTo(afterKey) > 0)
				.sorted((a, b) -> a.key().compareTo(b.key()))
				.limit(limit)
				.toList();
		}

		@Override
		public List<StorageObject> listAll(String afterKey, int limit) {
			return objects.stream()
				.filter(o -> o.state() == StorageObjectState.STORED)
				.filter(o -> afterKey == null || o.key().compareTo(afterKey) > 0)
				.sorted((a, b) -> a.key().compareTo(b.key()))
				.limit(limit)
				.toList();
		}

		@Override
		public void savePlacement(StoragePlacement placement) {
			placements.removeIf(p -> p.objectId().equals(placement.objectId())
				&& p.replica() == placement.replica());
			placements.add(placement);
		}

		@Override
		public List<StoragePlacement> placements(String objectId) {
			return placements.stream().filter(p -> p.objectId().equals(objectId)).toList();
		}

		@Override
		public Optional<StoragePlacement> placementOn(String objectId, String nodeId) {
			return placements.stream()
				.filter(p -> p.objectId().equals(objectId) && p.nodeId().equals(nodeId))
				.findFirst();
		}

		@Override
		public List<StoragePlacement> placementsOnNode(String nodeId) {
			return placements.stream().filter(p -> p.nodeId().equals(nodeId)).toList();
		}
	}
}