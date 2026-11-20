package com.socialcoisa.rede.network.adapters.in.web;

import java.time.Instant;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.network.application.StorageCatalogService;
import com.socialcoisa.rede.network.application.StorageLifecycle;
import com.socialcoisa.rede.network.application.StoragePendingRelease;
import com.socialcoisa.rede.network.domain.StorageBucketPolicy;
import com.socialcoisa.rede.network.domain.StorageObject;
import com.socialcoisa.rede.shared.web.AdminGuard;
import com.socialcoisa.rede.network.application.StorageReplicaAdoption;
import com.socialcoisa.rede.shared.audit.AuditTrail;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

/**
 * O ciclo de vida do Storage pelo painel (brief §14): política por bucket, histórico de versões,
 * restauração e a varredura de expiração/retenção.
 *
 * <p>Só admin: expiração e retenção decidem quando um arquivo de uma pessoa deixa de ser servível.
 * É a mesma fronteira das regiões e das tarefas — o que muda o que a rede entrega não fica com o
 * participante.</p>
 */
@RestController
@RequestMapping("/admin/network/storage")
class AdminNetworkStorageLifecycleController {

	private final StorageCatalogService storage;
	private final StorageLifecycle lifecycle;
	private final StorageReplicaAdoption replicaAdoption;
	private final StoragePendingRelease pendingRelease;
	private final CurrentUserResolver currentUserResolver;
	private final AdminGuard adminGuard;
	private final AuditTrail audit;

	AdminNetworkStorageLifecycleController(StorageCatalogService storage, StorageLifecycle lifecycle,
			StorageReplicaAdoption replicaAdoption, StoragePendingRelease pendingRelease,
			CurrentUserResolver currentUserResolver, AdminGuard adminGuard, AuditTrail audit) {
		this.storage = storage;
		this.lifecycle = lifecycle;
		this.replicaAdoption = replicaAdoption;
		this.pendingRelease = pendingRelease;
		this.currentUserResolver = currentUserResolver;
		this.adminGuard = adminGuard;
		this.audit = audit;
	}

	/** Guarda de TODA rota deste controller: a recusa tem de ser de admin, não de sessão. */
	private CurrentUser admin(String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		adminGuard.requireAdmin(user);
		return user;
	}

	/** A política vigente do bucket. Bucket sem linha responde os padrões, com {@code configured=false}. */
	@GetMapping("/buckets/{bucket}/policy")
	PolicyView policy(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String bucket) {
		admin(sessionToken);
		StorageBucketPolicy policy = storage.bucketPolicy(bucket);
		return new PolicyView(policy.bucket(), policy.versioning(), policy.retainSupersededDays(),
			policy.expireAfterDays(), policy.updatedBy(), policy.updatedAt(), policy.updatedBy() != null);
	}

	@PutMapping("/buckets/{bucket}/policy")
	PolicyView putPolicy(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String bucket, @RequestBody PolicyRequest request) {
		CurrentUser admin = admin(sessionToken);
		if (request == null) {
			throw new ApiException(400, "STORAGE_POLICY_REQUIRED",
				"Informe a politica do bucket.");
		}
		StorageBucketPolicy saved = storage.putBucketPolicy(admin.id().value(), bucket, request.versioning(),
			request.retainSupersededDays(), request.expireAfterDays());
		return new PolicyView(saved.bucket(), saved.versioning(), saved.retainSupersededDays(),
			saved.expireAfterDays(), saved.updatedBy(), saved.updatedAt(), true);
	}

	/** O histórico da chave, da versão mais antiga para a mais nova — a corrente é a última. */
	@GetMapping("/objects/versions")
	List<VersionView> versions(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestParam String bucket, @RequestParam String key) {
		admin(sessionToken);
		return storage.versions(bucket, key).stream().map(VersionView::of).toList();
	}

	/** Restaura uma versão anterior como versão nova da chave. */
	@PostMapping("/objects/restore")
	VersionView restore(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestBody(required = false) RestoreRequest request) {
		CurrentUser admin = admin(sessionToken);
		if (request == null || request.bucket() == null || request.key() == null) {
			throw new ApiException(400, "STORAGE_RESTORE_INVALID",
				"Informe o bucket, a chave e a versao a restaurar.");
		}
		return VersionView.of(storage.restore(admin.id().value(), request.bucket(), request.key(), request.version()));
	}

	/** Roda expiração e retenção. {@code dryRun} mostra o que sairia sem marcar nada. */
	@PostMapping("/lifecycle/run")
	LifecycleView run(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestParam(defaultValue = "true") boolean dryRun) {
		admin(sessionToken);
		return LifecycleView.of(lifecycle.run(dryRun));
	}

	/**
	 * O que hoje está <b>só</b> na réplica 0 (doc 13 §5.10): gravado e servível, sem cópia nem designação
	 * de nó. É a leitura que o operador faz antes de pedir a cópia.
	 */
	@GetMapping("/replica-zero")
	StorageReplicaAdoption.Report replicaZero(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		admin(sessionToken);
		return replicaAdoption.preview();
	}

	/**
	 * Pede a cópia de cada objeto que só tem a réplica 0, designando um nó com a mesma régua de
	 * colocação do upload. O padrão é <b>seco</b>: pedir transferência de banda de um participante é
	 * ato de operador, com o número na mão.
	 *
	 * <p>A designação nasce {@code PENDING_REMOTE} e só vira cópia quando o nó confirma — quem manda
	 * gravar é o despacho da réplica, não esta rota. Idempotente: objeto que já tem qualquer colocação
	 * de nó (inclusive pendente) não é tocado.</p>
	 */
	@PostMapping("/replica-zero")
	StorageReplicaAdoption.Report adoptReplicaZero(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestParam(name = "dryRun", defaultValue = "true") boolean dryRun) {
		CurrentUser admin = admin(sessionToken);
		StorageReplicaAdoption.Report relatorio = replicaAdoption.run(dryRun);
		if (!dryRun && !relatorio.adopted().isEmpty()) {
			audit.record(admin.id().value(), "STORAGE_REPLICA_ADOPTED", "storage",
				"{\"objects\":" + relatorio.adopted().size() + "}");
		}
		return relatorio;
	}

	/**
	 * As designações que ainda esperam confirmação, da mais antiga para a mais nova, com a idade e se
	 * já passaram do prazo (doc 13 §5.11).
	 *
	 * <p>É a leitura que faltava para uma {@code PENDING_REMOTE} perdida: o healer a responde
	 * {@code ALREADY_DESIGNATED} e a adoção de réplica única a pula, então sem esta lista o operador
	 * não tinha como saber <b>qual</b> designação está segurando a lacuna.</p>
	 */
	@GetMapping("/pending")
	StoragePendingRelease.Report pending(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestParam(defaultValue = "0") int limit) {
		admin(sessionToken);
		return pendingRelease.pending(limit);
	}

	/**
	 * Solta as designações vencidas: {@code PENDING_REMOTE} sem confirmação há mais que
	 * {@code maxAgeMinutes} (padrão {@code rede.storage.replication.pending-max-age-minutes}) vira
	 * {@code GONE}, e a lacuna volta a ser curável pelo {@code /storage/heal} e pela adoção de réplica
	 * única. O padrão é <b>seco</b>.
	 *
	 * <p>Não liga o auto-heal: é o ato de operador que antes só existia dentro do worker, que nasce
	 * desligado. Não apaga corpo de nó e não designa a cópia nova — só devolve a lacuna a quem cura.</p>
	 */
	@PostMapping("/pending/release")
	StoragePendingRelease.Report releasePending(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestParam(name = "maxAgeMinutes", required = false) Integer maxAgeMinutes,
			@RequestParam(name = "limit", defaultValue = "0") int limit,
			@RequestParam(name = "dryRun", defaultValue = "true") boolean dryRun) {
		CurrentUser admin = admin(sessionToken);
		StoragePendingRelease.Report relatorio = pendingRelease.releaseExpired(admin.id().value(), maxAgeMinutes,
			limit, dryRun);
		if (!dryRun && !relatorio.pending().isEmpty()) {
			audit.record(admin.id().value(), "STORAGE_PENDING_RELEASE_RUN", "storage",
				"{\"released\":" + relatorio.pending().size() + ",\"maxAgeMinutes\":" + relatorio.maxAgeMinutes()
					+ "}");
		}
		return relatorio;
	}

	record PolicyRequest(boolean versioning, Integer retainSupersededDays, Integer expireAfterDays) {
	}

	record RestoreRequest(String bucket, String key, long version) {
	}

	record PolicyView(String bucket, boolean versioning, Integer retainSupersededDays, Integer expireAfterDays,
			String updatedBy, Instant updatedAt, boolean configured) {
	}

	record VersionView(String objectId, String key, long version, String state, Long bytes, String sha256,
			boolean current, String supersededBy, Instant createdAt, Instant expiresAt) {

		static VersionView of(StorageObject object) {
			return new VersionView(object.id(), object.key(), object.version(), object.state().name(), object.bytes(),
				object.sha256(), object.currentVersion(), object.supersededBy(), object.createdAt(),
				object.expiresAt());
		}
	}

	record LifecycleView(boolean dryRun, Instant generatedAt, int expired, int retained,
			List<StorageLifecycle.Expired> expiredObjects, List<StorageLifecycle.Purged> purgedVersions,
			boolean truncated) {

		static LifecycleView of(StorageLifecycle.Report report) {
			return new LifecycleView(report.dryRun(), report.generatedAt(), report.expired(), report.retained(),
				report.expiredObjects(), report.purgedVersions(), report.truncated());
		}
	}
}