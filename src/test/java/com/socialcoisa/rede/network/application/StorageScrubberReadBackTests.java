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

import com.socialcoisa.rede.network.application.port.out.NetworkNodeRepository;
import com.socialcoisa.rede.network.application.port.out.StorageCatalogRepository;
import com.socialcoisa.rede.network.domain.NetworkNode;
import com.socialcoisa.rede.network.domain.NodeProfile;
import com.socialcoisa.rede.network.domain.NodeStatus;
import com.socialcoisa.rede.network.domain.ObjectMetadata;
import com.socialcoisa.rede.network.domain.StorageObject;
import com.socialcoisa.rede.network.domain.StorageRebalance;
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
	private NetworkNodeRepository nodes;
	private AuditTrail audit;
	private NodeBodyStore nodeBodies;
	private StorageCatalogService service;
	private StorageScrubber scrubber;

	@BeforeEach
	void setUp() {
		catalog = new InMemoryCatalog();
		nodes = mock(NetworkNodeRepository.class);
		audit = mock(AuditTrail.class);
		// O mesmo diretório que o processo usa quando roda junto do nó local. Fica vazio nos testes
		// de releitura da réplica 0; o teste do sumiço é que grava nele.
		nodeBodies = new NodeBodyStore(root.resolve("no-local/objects").toString());
		service = new StorageCatalogService(catalog, audit, Clock.fixed(NOW, ZoneOffset.UTC), 10_000, root.toString(),
			stubPlacement(), nodeBodies);
		scrubber = new StorageScrubber(catalog, stubPlacement(), audit, Clock.fixed(NOW, ZoneOffset.UTC), service,
			nodeBodies, nodes);
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
	void sumico_da_replica_zero_nao_e_escondido_pelo_corpo_do_no_local() throws Exception {
		StorageObject object = gravado(body("conteudo do arquivo"));
		// A réplica 0 some da raiz do Control Plane -- mas o MESMO corpo está no diretório do nó
		// local, porque o nó também guarda uma cópia deste objeto.
		Files.delete(service.bodyPath(object.id()));
		nodeBodies.write(object.id(), new ByteArrayInputStream(body("conteudo do arquivo")));
		// Cópia confirmada em OUTRO nó: não é relida daqui e não pode ser acusada pela falta local.
		catalog.savePlacement(new StoragePlacement(object.id(), 1, "nd_replica", object.bytes(), object.sha256(),
			StoragePlacement.State.OK, NOW));

		StorageScrubber.Report report = scrubber.scan("fotos", 10, true);

		// Uma releitura por objeto (a réplica 0), não uma por cópia: antes o laço relia o arquivo
		// local uma vez por colocação e contava 2.
		assertThat(report.bodiesRelidos()).isEqualTo(1);
		assertThat(report.divergent()).hasSize(1);
		StorageScrubber.Report.Divergent divergente = report.divergent().get(0);
		assertThat(divergente.replica()).isZero();
		assertThat(divergente.nodeId()).isEqualTo("nd_ctrl");
		// Digest vazio = "não consegui ler" — a réplica 0 sumiu, o corpo do nó local não a substitui.
		assertThat(divergente.actualSha256()).isEmpty();
	}

	/**
	 * O corpo do nó local entra na releitura. Antes ele era um ponto cego: objeto replicado para o
	 * nó local podia ser trocado no disco dele e a varredura continuava dizendo "conferido", porque
	 * só a réplica 0 era relida. A gotcha: sem verificar o dono do disco, qualquer colocação no id
	 * deste processo seria relida como se fosse dele.
	 */
	@Test
	void corpo_do_no_local_com_digest_diferente_e_reportado_com_a_replica_e_o_no_certos() throws Exception {
		StorageObject object = gravado(body("conteudo do arquivo"));
		Files.delete(service.bodyPath(object.id()));
		nodeBodies.write(object.id(), new ByteArrayInputStream(body("CONTEUDO DO ARQUIVO")));
		provisionado("nd_local");
		catalog.savePlacement(new StoragePlacement(object.id(), 1, "nd_local", object.bytes(), object.sha256(),
			StoragePlacement.State.OK, NOW));

		StorageScrubber.Report report = scrubber.scan("fotos", 10, true);

		// A réplica 0 sumiu (divergente, digest vazio) e o corpo do nó local tem outro digest: duas
		// divergências, cada uma na cópia que a produziu. Nenhuma é escondida pela outra.
		assertThat(report.bodiesRelidos()).isEqualTo(2);
		assertThat(report.divergent()).hasSize(2);
		StorageScrubber.Report.Divergent noLocal = report.divergent().stream()
			.filter(d -> d.replica() == 1)
			.findFirst()
			.orElseThrow();
		assertThat(noLocal.nodeId()).isEqualTo("nd_local");
		assertThat(noLocal.expectedSha256()).isEqualTo(object.sha256());
		assertThat(noLocal.actualSha256()).isNotBlank().isNotEqualTo(object.sha256());
	}

	@Test
	void corpo_do_no_local_intacto_nao_e_reportado() throws Exception {
		StorageObject object = gravado(body("conteudo do arquivo"));
		Files.delete(service.bodyPath(object.id()));
		nodeBodies.write(object.id(), new ByteArrayInputStream(body("conteudo do arquivo")));
		provisionado("nd_local");
		catalog.savePlacement(new StoragePlacement(object.id(), 1, "nd_local", object.bytes(), object.sha256(),
			StoragePlacement.State.OK, NOW));

		StorageScrubber.Report report = scrubber.scan("fotos", 10, true);

		// Só a réplica 0 diverge (sumiu). O corpo do nó local confere e não vira acusação.
		assertThat(report.divergent()).hasSize(1);
		assertThat(report.divergent().get(0).replica()).isZero();
	}

	@Test
	void copia_de_outro_no_nao_e_relida_como_se_fosse_deste_disco() throws Exception {
		StorageObject object = gravado(body("conteudo do arquivo"));
		// Um arquivo com o mesmo id no diretório do nó local, de conteúdo diferente: ele NÃO é a
		// cópia do nd_replica, e relê-lo acusaria o outro nó por um arquivo que não é dele.
		nodeBodies.write(object.id(), new ByteArrayInputStream(body("OUTRA COISA AQUI!!")));
		provisionado("nd_local");
		catalog.savePlacement(new StoragePlacement(object.id(), 1, "nd_replica", object.bytes(), object.sha256(),
			StoragePlacement.State.OK, NOW));

		StorageScrubber.Report report = scrubber.scan("fotos", 10, true);

		assertThat(report.bodiesRelidos()).isEqualTo(1);
		assertThat(report.divergent()).isEmpty();
	}

	/** As credenciais que o runtime grava em $NODE_HOME: é delas que sai o dono do disco. */
	private void provisionado(String nodeId) throws Exception {
		Files.createDirectories(root.resolve("no-local"));
		Files.writeString(root.resolve("no-local/node.json"), "{\"nodeId\":\"" + nodeId + "\"}");
		Path chave = root.resolve("no-local/api-key");
		Files.writeString(chave, "chave-de-teste");
		Files.setPosixFilePermissions(chave, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
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
		public long bytesOwnedBy(String ownerUserId) {
			// A varredura não cobra cota: só o upload cobra, e o teste de cota é o do serviço.
			return 0L;
		}

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
			StorageObject object = StorageObject.current(id, bucket, key, contentType, null, null,
				StorageObjectState.PENDING, createdBy, now, now, null);
			objects.add(object);
			return object;
		}

		@Override
		public boolean markStored(String id, long bytes, String sha256, Instant now) {
			for (int i = 0; i < objects.size(); i++) {
				StorageObject o = objects.get(i);
				if (o.id().equals(id) && o.state() == StorageObjectState.PENDING) {
					objects.set(i, StorageObject.current(o.id(), o.bucket(), o.key(), o.contentType(), bytes, sha256,
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
		public boolean discardPending(String id) {
			throw new UnsupportedOperationException("nao usado neste teste");
		}
		@Override
		public boolean restorePending(String id) {
			for (int i = 0; i < objects.size(); i++) {
				StorageObject o = objects.get(i);
				if (o.id().equals(id) && o.state() == StorageObjectState.STORED) {
					objects.set(i, StorageObject.current(o.id(), o.bucket(), o.key(), o.contentType(), null, null,
						StorageObjectState.PENDING, o.createdBy(), o.createdAt(), java.time.Instant.now(), null));
					return true;
				}
			}
			return false;
		}


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
		public List<StoragePlacement> copies(String objectId) {
			return placements.stream().filter(p -> p.objectId().equals(objectId)).toList();
		}

		@Override
		public List<StoragePlacement> placements(String objectId) {
			return copies(objectId).stream().filter(p -> p.state() == StoragePlacement.State.OK).toList();
		}

		@Override
		public List<StorageObject> listDeletedBefore(java.time.Instant before, String afterBucket,
				String afterKey, int limit) {
			return objects.stream()
				.filter(o -> o.state() == com.socialcoisa.rede.network.domain.StorageObjectState.DELETED)
				.filter(o -> o.deletedAt() != null && !o.deletedAt().isAfter(before))
				.filter(o -> afterBucket == null
						|| o.bucket().compareTo(afterBucket) > 0
						|| (o.bucket().equals(afterBucket) && o.key().compareTo(afterKey) > 0))
				.sorted(java.util.Comparator.comparing(StorageObject::bucket).thenComparing(StorageObject::key))
				.limit(limit)
				.toList();
		}

		@Override
		public Optional<StoragePlacement> placementOn(String objectId, String nodeId) {
			return placements.stream()
				.filter(p -> p.objectId().equals(objectId) && p.nodeId().equals(nodeId))
				.findFirst();
		}

		@Override
		public java.util.Map<String, long[]> summarizeBuckets() {
			java.util.Map<String, long[]> porBucket = new java.util.TreeMap<>();
			for (StorageObject o : objects) {
				if (o.state() == StorageObjectState.DELETED) {
					continue;
				}
				long[] par = porBucket.computeIfAbsent(o.bucket(), k -> new long[2]);
				par[0]++;
				par[1] += o.bytes() == null ? 0 : o.bytes();
			}
			return porBucket;
		}

		public java.util.Map<String, long[]> summarizeObjects() {
			java.util.Map<String, long[]> porEstado = new java.util.LinkedHashMap<>();
			for (StorageObject o : objects) {
				long[] par = porEstado.computeIfAbsent(o.state().name(), k -> new long[2]);
				par[0]++;
				par[1] += o.bytes() == null ? 0 : o.bytes();
			}
			return porEstado;
		}

		@Override
		public java.util.Map<String, long[]> summarizePlacements() {
			java.util.Map<String, long[]> porEstado = new java.util.LinkedHashMap<>();
			for (StoragePlacement p : placements) {
				long[] par = porEstado.computeIfAbsent(p.state().name(), k -> new long[2]);
				par[0]++;
				par[1] += p.bytes();
			}
			return porEstado;
		}

		@Override
		public List<StoragePlacement> placementsOnNode(String nodeId) {
			return placements.stream().filter(p -> p.nodeId().equals(nodeId)).toList();
		}

		@Override
		public List<StoragePlacement> placementsInState(StoragePlacement.State state) {
			return placements.stream().filter(p -> p.state() == state).toList();
		}

		// --- Ciclo de vida (§14, V352): o fake guarda o mínimo que os serviços leem ---

		private final java.util.Map<String, com.socialcoisa.rede.network.domain.StorageBucketPolicy> policies =
			new java.util.LinkedHashMap<>();

		@Override
		public StorageObject insertVersion(String id, String bucket, String key, String contentType, String createdBy,
				Instant now, long version) {
			StorageObject object = new StorageObject(id, bucket, key, contentType, null, null,
				StorageObjectState.PENDING, createdBy, now, now, null, version, null, null, ObjectMetadata.EMPTY,
				null, null);
			objects.add(object);
			return object;
		}

		@Override
		public boolean markSuperseded(String bucket, String key, String supersededBy, Instant now) {
			for (int i = 0; i < objects.size(); i++) {
				StorageObject o = objects.get(i);
				if (o.bucket().equals(bucket) && o.key().equals(key) && o.currentVersion()) {
					objects.set(i, new StorageObject(o.id(), o.bucket(), o.key(), o.contentType(), o.bytes(),
						o.sha256(), o.state(), o.createdBy(), o.createdAt(), now, o.deletedAt(), o.version(),
						supersededBy, o.expiresAt(), o.metadata(), o.cacheControl(), o.contentDisposition()));
					return true;
				}
			}
			return false;
		}

		@Override
		public void setClientHeaders(String id, com.socialcoisa.rede.network.domain.ObjectMetadata metadata,
				String cacheControl, String contentDisposition) {
			for (int i = 0; i < objects.size(); i++) {
				StorageObject o = objects.get(i);
				if (o.id().equals(id)) {
					objects.set(i, new StorageObject(o.id(), o.bucket(), o.key(), o.contentType(), o.bytes(),
						o.sha256(), o.state(), o.createdBy(), o.createdAt(), o.updatedAt(), o.deletedAt(),
						o.version(), o.supersededBy(), o.expiresAt(), metadata, cacheControl, contentDisposition));
				}
			}
		}

		public void setExpiresAt(String id, Instant expiresAt) {
			for (int i = 0; i < objects.size(); i++) {
				StorageObject o = objects.get(i);
				if (o.id().equals(id)) {
					objects.set(i, new StorageObject(o.id(), o.bucket(), o.key(), o.contentType(), o.bytes(),
						o.sha256(), o.state(), o.createdBy(), o.createdAt(), o.updatedAt(), o.deletedAt(),
						o.version(), o.supersededBy(), expiresAt, o.metadata(), o.cacheControl(),
						o.contentDisposition()));
				}
			}
		}

		@Override
		public List<StorageObject> versions(String bucket, String key) {
			return objects.stream().filter(o -> o.bucket().equals(bucket) && o.key().equals(key)).toList();
		}

		@Override
		public List<StorageObject> expired(Instant now, int limit) {
			return objects.stream().filter(o -> o.expired(now)).limit(limit).toList();
		}

		@Override
		public List<StorageObject> supersededBefore(Instant cutoff, int limit) {
			return objects.stream().filter(o -> !o.currentVersion() && !o.updatedAt().isAfter(cutoff))
				.limit(limit).toList();
		}

		@Override
		public Optional<com.socialcoisa.rede.network.domain.StorageBucketPolicy> bucketPolicy(String bucket) {
			return Optional.ofNullable(policies.get(bucket));
		}

		@Override
		public com.socialcoisa.rede.network.domain.StorageBucketPolicy saveBucketPolicy(
				com.socialcoisa.rede.network.domain.StorageBucketPolicy policy) {
			policies.put(policy.bucket(), policy);
			return policy;
		}

		// --- Rebalanceamento (§16, V354): o fake guarda o mínimo ---

		private final java.util.List<StorageRebalance> rebalances = new java.util.ArrayList<>();

		@Override
		public void saveRebalance(StorageRebalance rebalance) {
			rebalances.add(rebalance);
		}

		@Override
		public java.util.Optional<StorageRebalance> activeRebalance(String objectId, int fromReplica) {
			return rebalances.stream()
				.filter(r -> r.objectId().equals(objectId) && r.fromReplica() == fromReplica
					&& r.state().active())
				.findFirst();
		}

		@Override
		public java.util.Optional<StorageRebalance> findRebalance(String id) {
			return rebalances.stream().filter(r -> r.id().equals(id)).findFirst();
		}

		@Override
		public java.util.List<StorageRebalance> activeRebalances(int limit) {
			return rebalances.stream().filter(r -> r.state().active()).limit(limit).toList();
		}

		@Override
		public boolean transitionRebalance(String id, StorageRebalance.State from, StorageRebalance.State to,
				String failureReason, Instant now) {
			for (int i = 0; i < rebalances.size(); i++) {
				StorageRebalance r = rebalances.get(i);
				if (r.id().equals(id) && r.state() == from) {
					rebalances.set(i, new StorageRebalance(r.id(), r.objectId(), r.fromReplica(), r.fromNodeId(),
						r.toReplica(), r.toNodeId(), to, failureReason, r.bytes(), r.bandwidthLimitMbps(),
						r.requestedBy(), r.createdAt(), now));
					return true;
				}
			}
			return false;
		}

		// --- Gateway S3 (§11, V356): os buckets do dono ---

		@Override
		public java.util.List<String> bucketsOf(String ownerUserId) {
			return objects.stream()
				.filter(o -> o.createdBy() != null && o.createdBy().equals(ownerUserId))
				.map(StorageObject::bucket)
				.distinct()
				.sorted()
				.toList();
		}
	}
}
