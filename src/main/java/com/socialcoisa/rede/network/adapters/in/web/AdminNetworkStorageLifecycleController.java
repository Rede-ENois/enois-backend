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
import com.socialcoisa.rede.network.domain.StorageBucketPolicy;
import com.socialcoisa.rede.network.domain.StorageObject;
import com.socialcoisa.rede.shared.web.AdminGuard;
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
	private final CurrentUserResolver currentUserResolver;
	private final AdminGuard adminGuard;

	AdminNetworkStorageLifecycleController(StorageCatalogService storage, StorageLifecycle lifecycle,
			CurrentUserResolver currentUserResolver, AdminGuard adminGuard) {
		this.storage = storage;
		this.lifecycle = lifecycle;
		this.currentUserResolver = currentUserResolver;
		this.adminGuard = adminGuard;
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

	private CurrentUser admin(String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		adminGuard.requireAdmin(user);
		return user;
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