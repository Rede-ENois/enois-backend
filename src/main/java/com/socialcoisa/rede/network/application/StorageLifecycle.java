package com.socialcoisa.rede.network.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.network.application.port.out.StorageCatalogRepository;
import com.socialcoisa.rede.network.domain.StorageBucketPolicy;
import com.socialcoisa.rede.network.domain.StorageObject;
import com.socialcoisa.rede.shared.audit.AuditTrail;

/**
 * O ciclo de vida automático do Storage (brief §14): <b>expiração</b> e <b>retenção de versão</b>.
 *
 * <p>As duas são políticas por bucket ({@link StorageBucketPolicy}) e as duas terminam do mesmo
 * jeito: <b>lápide, nunca apagar</b>. O corpo continua nos nós até a coleta do
 * {@link StorageTombstoneCollector} passar pela linha — apagar a linha aqui deixaria o catálogo sem
 * registro de um arquivo que ainda ocupa disco de participante, e sem nada para coletar. É a mesma
 * regra do {@link StorageCatalogService#delete}: remover é marcar.</p>
 *
 * <p><b>Expiração e retenção não se confundem:</b> expiração é o objeto corrente vencendo pelo
 * próprio prazo; retenção é a versão substituída sendo liberada depois do prazo que o bucket deu.
 * A expiração respeita versão corrente: uma versão antiga não é "servida", então vencê-la de novo
 * não muda nada.</p>
 */
@Service
public class StorageLifecycle {

	/** Teto por chamada: uma transação não varre o catálogo inteiro. */
	static final int MAX_OBJECTS = 200;

	private final StorageCatalogRepository catalog;
	private final AuditTrail audit;
	private final Clock clock;
	private final StorageCatalogService cota;

	/**
	 * @param cota o serviço que mantém a conta do participante em memória; {@code null} nos testes
	 *             que só medem a decisão de lapidar
	 */
	StorageLifecycle(StorageCatalogRepository catalog, AuditTrail audit, Clock clock,
			@org.springframework.lang.Nullable StorageCatalogService cota) {
		this.catalog = catalog;
		this.audit = audit;
		this.clock = clock;
		this.cota = cota;
	}

	/**
	 * A lápide tira o objeto da conta do banco ({@code bytesOwnedBy} exclui {@code DELETED}); o cache
	 * da cota tem de esquecer o número do dono, senão quem deixou vencer continua barrado.
	 */
	private void esqueceuAConta(StorageObject object) {
		if (cota != null) {
			cota.esquecerUsoDoParticipante(object.createdBy());
		}
	}

	/**
	 * Aplica o ciclo de vida de todos os buckets. {@code dryRun} mostra o que aconteceria sem
	 * marcar nada — o padrão do módulo para operação de coleta.
	 */
	@Transactional
	public Report run(boolean dryRun) {
		Instant now = clock.instant();
		List<Expired> expirados = new ArrayList<>();
		for (StorageObject object : catalog.expired(now, MAX_OBJECTS)) {
			if (dryRun) {
				expirados.add(new Expired(object.id(), object.bucket(), object.key(), object.version(),
					object.expiresAt()));
				continue;
			}
			// A marcação é por `id`, e cada versão é um objeto com id próprio (doc 17 §11.1): o
			// prazo vencido da versão ANTIGA lapida aquela versão, nunca a corrente que a substituiu.
			// Condicional no estado dentro do repositório: um upload que termine depois da leitura
			// (`PENDING` → `STORED`) não pode ser lapidado por um prazo de outra versão.
			if (catalog.markDeleted(object.id(), now)) {
				esqueceuAConta(object);
				expirados.add(new Expired(object.id(), object.bucket(), object.key(), object.version(),
					object.expiresAt()));
			}
		}

		List<Purged> liberados = new ArrayList<>();
		for (StorageObject version : catalog.supersededBefore(cutoff(now), MAX_OBJECTS)) {
			StorageBucketPolicy policy = catalog.bucketPolicy(version.bucket())
				.orElseGet(() -> StorageBucketPolicy.padrao(version.bucket()));
			// A varredura de repositório é global; a retenção é de cada bucket. Sem política, a
			// versão não é liberada — o padrão é guardar.
			Instant purgeAfter = policy.purgeAfterForSupersededAt(version.updatedAt());
			if (purgeAfter == null || purgeAfter.isAfter(now)) {
				continue;
			}
			if (dryRun) {
				liberados.add(new Purged(version.id(), version.bucket(), version.key(), version.version()));
				continue;
			}
			if (catalog.markDeleted(version.id(), now)) {
				esqueceuAConta(version);
				liberados.add(new Purged(version.id(), version.bucket(), version.key(), version.version()));
			}
		}

		if (!dryRun && (!expirados.isEmpty() || !liberados.isEmpty())) {
			audit.record("system", "STORAGE_LIFECYCLE", "storage",
				"{\"expired\":" + expirados.size() + ",\"retainedOut\":" + liberados.size() + "}");
		}
		return new Report(dryRun, now, expirados.size(), liberados.size(), List.copyOf(expirados),
			List.copyOf(liberados), Math.max(expirados.size(), liberados.size()) >= MAX_OBJECTS);
	}

	/**
	 * O corte para a leitura de versões substituídas: o menor prazo que algum bucket configurou.
	 *
	 * <p>O repositório não conhece a política, então a leitura é permissiva e o filtro fino é por
	 * bucket logo abaixo. O corte é agora: nenhuma retenção libra uma versão substituída <b>no
	 * futuro</b>, então nada anterior a agora pode ser liberado por política nenhuma.</p>
	 */
	private Instant cutoff(Instant now) {
		return now;
	}

	/**
	 * @param dryRun   nada foi gravado
	 * @param expired  versões correntes que venceram e receberam lápide
	 * @param retained versões substituídas cuja retenção do bucket venceu e receberam lápide
	 * @param truncated a varredura parou no teto; pode haver mais a processar
	 */
	public record Report(boolean dryRun, Instant generatedAt, int expired, int retained, List<Expired> expiredObjects,
			List<Purged> purgedVersions, boolean truncated) {
	}

	public record Expired(String objectId, String bucket, String key, long version, Instant expiresAt) {
	}

	public record Purged(String objectId, String bucket, String key, long version) {
	}
}