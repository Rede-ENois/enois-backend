package com.socialcoisa.rede.network.application.testsupport;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.socialcoisa.rede.network.application.port.out.StorageCatalogRepository;
import com.socialcoisa.rede.network.domain.StorageObject;
import com.socialcoisa.rede.network.domain.StorageObjectState;
import com.socialcoisa.rede.network.domain.StoragePlacement;
import com.socialcoisa.rede.network.domain.StorageRebalance;

/**
 * Catálogo em memória para os testes de serviço que não precisam de banco.
 *
 * <p>Vive fora do teste que nasceu com ele porque a cota por participante (doc 20 §10) é outro
 * assunto e precisa do mesmo catálogo. {@code bytesOwnedBy} aplica aqui a <b>mesma regra</b> do SQL
 * do adapter — versão corrente, sem lápide — para o teste da cota cobrar o serviço, e não o
 * {@code sum} do banco (esse tem teste próprio, pelo adapter).</p>
 */
public abstract class CatalogoEmMemoria implements StorageCatalogRepository {

	

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
	public boolean discardPending(String id) {
		throw new UnsupportedOperationException("nao usado neste teste");
	}
	@Override
	public boolean restorePending(String id) {
		return replace(id, o -> o.state() == StorageObjectState.STORED,
			o -> StorageObject.current(o.id(), o.bucket(), o.key(), o.contentType(), null, null,
				StorageObjectState.PENDING, o.createdBy(), o.createdAt(), java.time.Instant.now(), null));
	}


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
	public long bytesOwnedBy(String ownerUserId) {
		return byKey.values().stream()
			.filter(o -> ownerUserId.equals(o.createdBy()) && o.supersededBy() == null
					&& o.state() != StorageObjectState.DELETED)
			.mapToLong(o -> o.bytes() == null ? 0 : o.bytes())
			.sum();
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

	@Override
	public List<StoragePlacement> placementsInState(StoragePlacement.State state) {
		return placements.values().stream().filter(p -> p.state() == state).toList();
	}

	// --- Ciclo de vida (§14, V352): o fake guarda o mínimo que os serviços leem ---

	@Override
	public StorageObject insertVersion(String id, String bucket, String key, String contentType, String createdBy,
			Instant now, long version) {
		StorageObject object = new StorageObject(id, bucket, key, contentType, null, null,
			StorageObjectState.PENDING, createdBy, now, now, null, version, null, null,
			com.socialcoisa.rede.network.domain.ObjectMetadata.EMPTY, null, null);
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
			corrente.expiresAt(), corrente.metadata(), corrente.cacheControl(), corrente.contentDisposition()));
		return true;
	}

	@Override
	public void setExpiresAt(String id, Instant expiresAt) {
		replace(id, o -> true, o -> new StorageObject(o.id(), o.bucket(), o.key(), o.contentType(), o.bytes(),
			o.sha256(), o.state(), o.createdBy(), o.createdAt(), o.updatedAt(), o.deletedAt(), o.version(),
			o.supersededBy(), expiresAt, o.metadata(), o.cacheControl(), o.contentDisposition()));
	}

	@Override
	public void setClientHeaders(String id, com.socialcoisa.rede.network.domain.ObjectMetadata metadata,
			String cacheControl, String contentDisposition) {
		replace(id, o -> o.state() == StorageObjectState.PENDING,
			o -> new StorageObject(o.id(), o.bucket(), o.key(), o.contentType(), o.bytes(), o.sha256(),
				o.state(), o.createdBy(), o.createdAt(), o.updatedAt(), o.deletedAt(), o.version(),
				o.supersededBy(), o.expiresAt(), metadata, cacheControl, contentDisposition));
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

	// --- Gateway S3 (§11, V356): os buckets do dono ---

	@Override
	public java.util.List<String> bucketsOf(String ownerUserId) {
		return byKey.values().stream()
			.filter(o -> o.createdBy() != null && o.createdBy().equals(ownerUserId))
			.map(StorageObject::bucket)
			.distinct()
			.sorted()
			.toList();
	}
}
