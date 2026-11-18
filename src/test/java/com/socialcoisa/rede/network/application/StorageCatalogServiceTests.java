package com.socialcoisa.rede.network.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
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
import com.socialcoisa.rede.network.domain.StorageObject;
import com.socialcoisa.rede.network.domain.StorageRebalance;
import com.socialcoisa.rede.network.domain.StorageObjectState;
import com.socialcoisa.rede.network.domain.StoragePlacement;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.socialcoisa.rede.shared.audit.AuditTrail;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * O catálogo do Storage: o que o Control Plane grava é o que ele <b>mediu</b>.
 *
 * <p>O que estes testes fixam, e por quê:</p>
 * <ul>
 * <li><b>bytes e sha-256 vêm do corpo, nunca de um campo declarado</b> — é o mesmo critério do
 * {@code MediaResultVerifier}: "gravou" só vale quando o CP conferiu. Sem este teste, um serviço que
 * aceitasse um {@code bytes} no comando passaria e o catálogo mentiria sobre o arquivo;</li>
 * <li><b>uma cópia, no nó que gravou</b> — a colocação é o que torna o objeto recuperável depois
 * que o upload termina. Objeto STORED sem colocação é metadado divergente;</li>
 * <li><b>o corpo é escrito de verdade</b> no diretório autorizado: conferir hash de um fluxo
 * descartado daria "gravou" para um arquivo que não existe;</li>
 * <li><b>perfil de storage é obrigatório</b> — disco não dá permissão, do mesmo jeito que núcleo
 * não dá;</li>
 * <li><b>lápide não ressuscita</b> — upload que chega depois da remoção não devolve um objeto
 * servível, porque isso seria um download de arquivo que o dono já apagou.</li>
 * </ul>
 */
class StorageCatalogServiceTests {

	private static final Instant NOW = Instant.parse("2026-11-14T12:00:00Z");

	@TempDir
	Path root;

	private InMemoryCatalog catalog;
	private AuditTrail audit;
	private NetworkNodeRepository nodes;
	private StorageCatalogService service;

	@BeforeEach
	void setUp() {
		catalog = new InMemoryCatalog();
		audit = mock(AuditTrail.class);
		nodes = mock(NetworkNodeRepository.class);
		when(nodes.findCandidates(any(), anyInt())).thenReturn(List.of());
		service = new StorageCatalogService(catalog, audit, Clock.fixed(NOW, ZoneOffset.UTC), 10_000,
			root.toString(), placement(1), null);
	}

	/** O serviço de colocação com o número de cópias dado; por padrão, uma só (o Control Plane). */
	private StoragePlacementService placement(int replicas) {
		return new StoragePlacementService(nodes, catalog, replicas, 90);
	}

	private static byte[] body(String text) {
		return text.getBytes(StandardCharsets.UTF_8);
	}

	private static String sha256(byte[] body) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
	}

	private static NetworkNode storageNode() {
		return new NetworkNode("nd_store", "vps-casa", "user_1", NodeStatus.ACTIVE, Set.of(NodeProfile.NODE_STORAGE),
			"sa-east-1", "BR", "1.0.0", null, 2, 4096, 102400, 100, null, null, null, null, 0, null, null);
	}

	/** O mesmo nó com o disco livre que ele MEDIU no último heartbeat (V321). */
	private static NetworkNode storageNode(Long diskFreeMb) {
		return new NetworkNode("nd_store", "vps-casa", "user_1", NodeStatus.ACTIVE, Set.of(NodeProfile.NODE_STORAGE),
			"sa-east-1", "BR", "1.0.0", null, 2, 4096, 102400, 100, null, null, null, null, diskFreeMb, null, null, 0,
			null, null);
	}

	/** O mesmo nó com o teto que o participante cedeu e o uso que ele mediu em si (V344). */
	private static NetworkNode storageNodeCedendo(long declaredMb, Long usedMb) {
		return new NetworkNode("nd_store", "vps-casa", "user_1", NodeStatus.ACTIVE, Set.of(NodeProfile.NODE_STORAGE),
			"sa-east-1", "BR", "1.0.0", null, 2, 4096, (int) declaredMb, 100, null, null, null, null, 204800L, null,
			usedMb, 0, null, null);
	}

	/**
	 * O §7: 2 TB de disco, 500 GB reservados, 1,5 TB cedidos. Com 1,5 TB já ocupados, o disco livre
	 * (500 GB) passa folgado no piso de 1 GiB — mas a parte cedida acabou. Antes desta recusa o
	 * objeto ia para além do que a pessoa autorizou; agora o 507 diz qual número acabou, com o
	 * cedido, o ocupado e o piso na mensagem, para o operador saber se é o teto ou o disco.
	 */
	@Test
	void no_que_esgotou_o_espaco_cedido_recusa_com_o_numero() throws Exception {
		StorageObject object = service.beginUpload("user_1", "fotos", "gato.jpg", "image/jpeg");
		long declaradoMb = 1_500_000L;
		NetworkNode vazio = storageNodeCedendo(declaradoMb, declaradoMb - 10);

		assertThatThrownBy(() -> service.store(vazio, object.id(), new ByteArrayInputStream(body("abc"))))
			.isInstanceOf(ApiException.class)
			.hasMessageContaining("cedeu " + declaradoMb)
			.hasMessageContaining("ja ocupa " + (declaradoMb - 10));
		assertThat(Files.exists(service.bodyPath(object.id()))).isFalse();
	}

	/** Abaixo do piso de folga nada entra; exatamente no piso entra — o mesmo piso do disco. */
	@Test
	void folga_cedida_no_piso_ainda_recebe_o_corpo() throws Exception {
		StorageObject object = service.beginUpload("user_1", "fotos", "gato.jpg", "image/jpeg");

		service.store(storageNodeCedendo(102400L, 102400L - StorageCatalogService.MIN_DISK_FREE_MB), object.id(),
			new ByteArrayInputStream(body("abc")));

		assertThat(catalog.findById(object.id()).orElseThrow().servable()).isTrue();
	}

	/**
	 * Cópia interrompida, sobra de troca: arquivo no volume que o catálogo não confirma. O número
	 * que o nó mediu é o que pega esse caso. Os lados fazem o MAX ser o único que recusa — o
	 * catálogo confirma 10 MB e o nó mede os 102400: com o menor (10) sobraria folga e o corpo
	 * entraria.
	 */
	@Test
	void uso_medido_pelo_no_recusa_mesmo_sem_copia_confirmada() throws Exception {
		StorageObject object = service.beginUpload("user_1", "fotos", "gato.jpg", "image/jpeg");
		// Uma cópia confirmada pequena, pelo caminho real do catálogo.
		catalog.savePlacement(new StoragePlacement(object.id(), 0, "nd_store", 10 * 1024 * 1024L, "abc",
			StoragePlacement.State.OK, Instant.now()));

		assertThatThrownBy(() -> service.store(storageNodeCedendo(102400L, 102400L),
			object.id(), new ByteArrayInputStream(body("abc"))))
			.isInstanceOf(ApiException.class)
			.hasMessageContaining("ja ocupa 102400");
	}

	@Test
	void no_com_disco_livre_medido_abaixo_do_piso_nao_recebe_o_corpo() throws Exception {
		StorageObject object = service.beginUpload("user_1", "fotos", "gato.jpg", "image/jpeg");
		NetworkNode apertado = storageNode(StorageCatalogService.MIN_DISK_FREE_MB - 1);

		assertThatThrownBy(() -> service.store(apertado, object.id(), new ByteArrayInputStream(body("abc"))))
			.isInstanceOf(ApiException.class)
			.hasMessageContaining("livres");
		// Recusar ANTES de escrever: sem corpo no disco e sem colocação que diga que gravou.
		assertThat(Files.exists(service.bodyPath(object.id()))).isFalse();
		assertThat(service.location(object.id())).isEmpty();
		assertThat(catalog.findById(object.id()).orElseThrow().servable()).isFalse();
	}

	@Test
	void no_exatamente_no_piso_ainda_recebe_o_corpo() throws Exception {
		StorageObject object = service.beginUpload("user_1", "fotos", "gato.jpg", "image/jpeg");

		service.store(storageNode(StorageCatalogService.MIN_DISK_FREE_MB), object.id(),
			new ByteArrayInputStream(body("abc")));

		assertThat(catalog.findById(object.id()).orElseThrow().servable()).isTrue();
	}

	@Test
	void no_que_nao_reportou_disco_nao_e_recusado_por_isso() throws Exception {
		StorageObject object = service.beginUpload("user_1", "fotos", "gato.jpg", "image/jpeg");

		// null = "não reportou" (runtime anterior a V321). Não medir não é estar sem espaço: recusar
		// aqui tiraria todo nó não atualizado do caminho de escrita por falta de informação, não por
		// indisponibilidade — e o disco real poderia estar com terabytes livres.
		service.store(storageNode((Long) null), object.id(), new ByteArrayInputStream(body("abc")));

		assertThat(catalog.findById(object.id()).orElseThrow().servable()).isTrue();
	}

	@Test
	void o_que_fica_no_catalogo_e_o_medido_do_corpo_nao_o_declarado() throws Exception {
		StorageObject object = service.beginUpload("user_1", "fotos", "gato.jpg", "image/jpeg");
		byte[] content = body("conteudo do arquivo");

		StorageCatalogService.Stored stored = service.store(storageNode(), object.id(),
			new ByteArrayInputStream(content));

		assertThat(stored.bytes()).isEqualTo(content.length);
		assertThat(stored.sha256()).isEqualTo(sha256(content));
		StorageObject saved = catalog.findById(object.id()).orElseThrow();
		assertThat(saved.state()).isEqualTo(StorageObjectState.STORED);
		assertThat(saved.bytes()).isEqualTo((long) content.length);
		assertThat(saved.sha256()).isEqualTo(sha256(content));
		// Gravar objeto é evento auditado no mesmo trilho das demais ações do módulo.
		verify(audit).record(eq("nd_store"), eq("STORAGE_OBJECT_STORED"), eq(object.id()), anyString());
	}

	@Test
	void a_copia_fica_registrada_no_no_que_gravou_e_o_corpo_esta_no_disco() throws Exception {
		StorageObject object = service.beginUpload("user_1", "fotos", "gato.jpg", "image/jpeg");
		byte[] content = body("abc");

		service.store(storageNode(), object.id(), new ByteArrayInputStream(content));

		List<StoragePlacement> placements = service.location(object.id());
		assertThat(placements).hasSize(1);
		assertThat(placements.get(0).nodeId()).isEqualTo("nd_store");
		assertThat(placements.get(0).replica()).isZero();
		// O corpo tem de estar no diretório autorizado: medir um fluxo descartado daria "gravou"
		// para um arquivo que não existe.
		assertThat(Files.readAllBytes(service.bodyPath(object.id()))).isEqualTo(content);
	}

	@Test
	void sem_perfil_de_storage_o_no_nao_grava_mesmo_com_a_chave_certa() {
		StorageObject object = service.beginUpload("user_1", "fotos", "gato.jpg", "image/jpeg");
		NetworkNode semPerfil = new NetworkNode("nd_api", "api", "user_1", NodeStatus.ACTIVE,
			Set.of(NodeProfile.NODE_API), "sa-east-1", "BR", "1.0.0", null, 2, 4096, 10240, 100, null, null, null,
			null, 0, null, null);

		assertThatThrownBy(() -> service.store(semPerfil, object.id(), new ByteArrayInputStream(body("x"))))
			.isInstanceOf(ApiException.class)
			.hasMessageContaining("perfil de armazenamento");
		assertThat(catalog.findById(object.id()).orElseThrow().state()).isEqualTo(StorageObjectState.PENDING);
	}

	@Test
	void corpo_vazio_nao_vira_objeto_armazenado() {
		StorageObject object = service.beginUpload("user_1", "fotos", "vazio.bin", null);

		assertThatThrownBy(() -> service.store(storageNode(), object.id(), new ByteArrayInputStream(new byte[0])))
			.isInstanceOf(ApiException.class)
			.hasMessageContaining("vazio");
		assertThat(catalog.findById(object.id()).orElseThrow().servable()).isFalse();
	}

	@Test
	void acima_do_teto_o_corpo_e_recusado_e_o_objeto_fica_pendente() {
		StorageCatalogService pequeno = new StorageCatalogService(catalog, audit,
			Clock.fixed(NOW, ZoneOffset.UTC), 4, root.toString(), placement(1), null);
		StorageObject object = pequeno.beginUpload("user_1", "fotos", "grande.bin", null);

		assertThatThrownBy(() -> pequeno.store(storageNode(), object.id(),
			new ByteArrayInputStream(body("maior que o teto"))))
			.isInstanceOf(ApiException.class)
			.hasMessageContaining("teto");
		assertThat(catalog.findById(object.id()).orElseThrow().servable()).isFalse();
	}

	@Test
	void objeto_pendente_nao_e_servivel_mesmo_estando_no_catalogo() {
		service.beginUpload("user_1", "fotos", "incompleto.jpg", "image/jpeg");

		assertThatThrownBy(() -> service.requireDownloadable("fotos", "incompleto.jpg"))
			.isInstanceOf(ApiException.class)
			.hasMessageContaining("nao encontrado");
	}

	@Test
	void depois_da_lapide_o_upload_novo_nao_ressuscita_o_objeto() {
		StorageObject object = service.beginUpload("user_1", "fotos", "gato.jpg", "image/jpeg");
		service.store(storageNode(), object.id(), new ByteArrayInputStream(body("v1")));
		service.delete("user_1", "fotos", "gato.jpg");

		assertThatThrownBy(() -> service.beginUpload("user_1", "fotos", "gato.jpg", "image/jpeg"))
			.isInstanceOf(ApiException.class)
			.hasMessageContaining("removida");
		assertThat(catalog.findById(object.id()).orElseThrow().state()).isEqualTo(StorageObjectState.DELETED);
	}

	@Test
	void o_mesmo_upload_repetido_devolve_o_objeto_que_ja_existe() {
		StorageObject first = service.beginUpload("user_1", "fotos", "gato.jpg", "image/jpeg");
		StorageObject second = service.beginUpload("user_1", "fotos", "gato.jpg", "image/png");

		assertThat(second.id()).isEqualTo(first.id());
		// O content-type da primeira criação vale: o objeto retomado não troca de tipo no meio.
		assertThat(second.contentType()).isEqualTo("image/jpeg");
	}

	@Test
	void a_chave_do_objeto_nao_e_normalizada_pela_gravacao_do_upload() {
		StorageObject object = service.beginUpload("user_1", "fotos", "Foto.JPG", null);

		assertThat(object.key()).isEqualTo("Foto.JPG");
	}

	@Test
	void bucket_com_maiuscula_e_recusado_para_nao_criar_duas_grafias_do_mesmo_bucket() {
		assertThatThrownBy(() -> service.beginUpload("user_1", "Fotos", "a.jpg", null))
			.isInstanceOf(ApiException.class)
			.hasMessageContaining("minusculo");
	}

	/** Catálogo em memória: a porta é o que importa aqui, não o SQL (coberto pelos testes de schema). */
	private static final class InMemoryCatalog implements StorageCatalogRepository {

		private final java.util.Map<String, StorageObject> byKey = new java.util.LinkedHashMap<>();
		private final java.util.Map<String, StoragePlacement> placements = new java.util.LinkedHashMap<>();

		@Override
		public Optional<StorageObject> find(String bucket, String key) {
			return Optional.ofNullable(byKey.get(bucket + "/" + key));
		}

		@Override
		public Optional<StorageObject> findById(String id) {
			return byKey.values().stream().filter(o -> o.id().equals(id)).findFirst();
		}

		@Override
		public Optional<StorageObject> findByDigest(String sha256) {
			return byKey.values().stream()
				.filter(o -> o.state() == StorageObjectState.STORED && sha256.equals(o.sha256()))
				.findFirst();
		}

		@Override
		public java.util.List<StorageObject> listAll(String afterKey, int limit) {
			return byKey.values().stream()
				.filter(o -> o.state() != StorageObjectState.DELETED)
				.filter(o -> afterKey == null || o.key().compareTo(afterKey) >= 0)
				.sorted(java.util.Comparator.comparing(StorageObject::bucket).thenComparing(StorageObject::key))
				.limit(limit)
				.toList();
		}

		@Override
		public StorageObject createPending(String id, String bucket, String key, String contentType, String createdBy,
				Instant now) {
			StorageObject existing = byKey.get(bucket + "/" + key);
			if (existing != null) {
				return existing;
			}
			StorageObject object = StorageObject.current(id, bucket, key, contentType, null, null,
				StorageObjectState.PENDING, createdBy, now, now, null);
			byKey.put(bucket + "/" + key, object);
			return object;
		}

		@Override
		public boolean markStored(String id, long bytes, String sha256, Instant now) {
			return replace(id, o -> o.state() == StorageObjectState.PENDING,
				o -> StorageObject.current(o.id(), o.bucket(), o.key(), o.contentType(), bytes, sha256,
					StorageObjectState.STORED, o.createdBy(), o.createdAt(), now, null));
		}

		@Override
		public boolean markDeleted(String id, Instant now) {
			return replace(id, o -> o.state() != StorageObjectState.DELETED,
				o -> StorageObject.current(o.id(), o.bucket(), o.key(), o.contentType(), o.bytes(), o.sha256(),
					StorageObjectState.DELETED, o.createdBy(), o.createdAt(), now, now));
		}

		private boolean replace(String id, java.util.function.Predicate<StorageObject> when,
				java.util.function.UnaryOperator<StorageObject> change) {
			Optional<StorageObject> current = findById(id);
			if (current.isEmpty() || !when.test(current.get())) {
				return false;
			}
			StorageObject changed = change.apply(current.get());
			byKey.put(changed.bucket() + "/" + changed.key(), changed);
			return true;
		}

		@Override
		public List<StorageObject> list(String bucket, String prefix, String afterKey, int limit) {
			return byKey.values().stream()
				.filter(o -> o.bucket().equals(bucket) && o.key().startsWith(prefix == null ? "" : prefix))
				.filter(o -> afterKey == null || o.key().compareTo(afterKey) > 0)
				.filter(o -> o.state() != StorageObjectState.DELETED)
				.sorted((a, b) -> a.key().compareTo(b.key()))
				.limit(limit)
				.toList();
		}

		@Override
		public void savePlacement(StoragePlacement placement) {
			placements.put(placement.objectId() + "/" + placement.replica(), placement);
		}

		@Override
		public List<StoragePlacement> copies(String objectId) {
			return placements.values().stream().filter(p -> p.objectId().equals(objectId)).toList();
		}

		@Override
		public List<StoragePlacement> placements(String objectId) {
			return copies(objectId).stream().filter(p -> p.state() == StoragePlacement.State.OK).toList();
		}

		@Override
		public List<StorageObject> listDeletedBefore(java.time.Instant before, String afterBucket,
				String afterKey, int limit) {
			var encontrados = byKey.values().stream()
				.filter(o -> o.state() == com.socialcoisa.rede.network.domain.StorageObjectState.DELETED)
				.filter(o -> o.deletedAt() != null && !o.deletedAt().isAfter(before))
				.filter(o -> afterBucket == null
						|| o.bucket().compareTo(afterBucket) > 0
						|| (o.bucket().equals(afterBucket) && o.key().compareTo(afterKey) > 0))
				.sorted(java.util.Comparator.comparing(StorageObject::bucket).thenComparing(StorageObject::key))
				.limit(limit)
				.toList();
			return encontrados;
		}

		@Override
		public Optional<StoragePlacement> placementOn(String objectId, String nodeId) {
			return placements.values().stream()
				.filter(p -> p.objectId().equals(objectId) && p.nodeId().equals(nodeId)
					&& p.state() == StoragePlacement.State.OK)
				.findFirst();
		}

		@Override
		public java.util.Map<String, long[]> summarizeBuckets() {
			java.util.Map<String, long[]> porBucket = new java.util.TreeMap<>();
			for (StorageObject o : byKey.values()) {
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
			for (StorageObject o : byKey.values()) {
				long[] par = porEstado.computeIfAbsent(o.state().name(), k -> new long[2]);
				par[0]++;
				par[1] += o.bytes() == null ? 0 : o.bytes();
			}
			return porEstado;
		}

		@Override
		public java.util.Map<String, long[]> summarizePlacements() {
			java.util.Map<String, long[]> porEstado = new java.util.LinkedHashMap<>();
			for (StoragePlacement p : placements.values()) {
				long[] par = porEstado.computeIfAbsent(p.state().name(), k -> new long[2]);
				par[0]++;
				par[1] += p.bytes();
			}
			return porEstado;
		}

		@Override
		public List<StoragePlacement> placementsOnNode(String nodeId) {
			return placements.values().stream().filter(p -> p.nodeId().equals(nodeId)).toList();
		}

		// --- Ciclo de vida (§14, V352): o fake guarda o mínimo que os serviços leem ---

		@Override
		public StorageObject insertVersion(String id, String bucket, String key, String contentType, String createdBy,
				Instant now, long version) {
			StorageObject object = new StorageObject(id, bucket, key, contentType, null, null,
				StorageObjectState.PENDING, createdBy, now, now, null, version, null, null);
			byKey.put(bucket + "/" + key, object);
			return object;
		}

		@Override
		public boolean markSuperseded(String bucket, String key, String supersededBy, Instant now) {
			StorageObject corrente = byKey.get(bucket + "/" + key);
			if (corrente == null || !corrente.currentVersion()) {
				return false;
			}
			byKey.put(bucket + "/" + key, new StorageObject(corrente.id(), corrente.bucket(), corrente.key(),
				corrente.contentType(), corrente.bytes(), corrente.sha256(), corrente.state(), corrente.createdBy(),
				corrente.createdAt(), now, corrente.deletedAt(), corrente.version(), supersededBy,
				corrente.expiresAt()));
			return true;
		}

		@Override
		public void setExpiresAt(String id, Instant expiresAt) {
			replace(id, o -> true, o -> new StorageObject(o.id(), o.bucket(), o.key(), o.contentType(), o.bytes(),
				o.sha256(), o.state(), o.createdBy(), o.createdAt(), o.updatedAt(), o.deletedAt(), o.version(),
				o.supersededBy(), expiresAt));
		}

		@Override
		public List<StorageObject> versions(String bucket, String key) {
			return byKey.values().stream().filter(o -> o.bucket().equals(bucket) && o.key().equals(key)).toList();
		}

		@Override
		public List<StorageObject> expired(Instant now, int limit) {
			return byKey.values().stream().filter(o -> o.expired(now)).limit(limit).toList();
		}

		@Override
		public List<StorageObject> supersededBefore(Instant cutoff, int limit) {
			return byKey.values().stream().filter(o -> !o.currentVersion() && !o.updatedAt().isAfter(cutoff))
				.limit(limit).toList();
		}

		private final java.util.Map<String, com.socialcoisa.rede.network.domain.StorageBucketPolicy> policies =
			new java.util.LinkedHashMap<>();

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
	}
}