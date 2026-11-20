package com.socialcoisa.rede.network.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.network.application.port.out.StorageCatalogRepository;
import com.socialcoisa.rede.network.domain.StorageObject;
import com.socialcoisa.rede.network.domain.StoragePlacement;
import com.socialcoisa.rede.shared.audit.AuditTrail;

/**
 * A designação que ninguém confirmou vira {@code GONE} — e a lacuna volta a ser curável.
 *
 * <p><b>O buraco que isto fecha.</b> A soltura existia, mas só dentro de
 * {@link StorageReplicationWorker}, que nasce <b>desligado</b>
 * ({@code rede.storage.replication.auto-heal-enabled=false}). Na instalação de fábrica, uma
 * {@code PENDING_REMOTE} cujo nó sumiu depois do despacho — tarefa perdida, disco cheio, nó
 * revogado — durava para sempre. O {@code StorageHealer} a lia como "a lacuna tem dono"
 * ({@code ALREADY_DESIGNATED}) e a adoção de réplica única também a pulava: o operador via a
 * durabilidade baixa e <b>não tinha rota</b> para agir, porque o único ato que soltava a pendência
 * estava atrás de ligar o tráfego automático de corpo.</p>
 *
 * <p><b>A regra, uma só.</b> O worker passou a chamar este serviço em vez de ter a própria cópia da
 * varredura. A idade é a de {@code verified_at}, que {@code savePlacement} grava como o instante da
 * designação; o prazo é o mesmo {@code rede.storage.replication.pending-max-age-minutes} (60) das
 * duas mãos. {@code null} conta como vencida: não dá para provar que a designação é nova, e a
 * pendência sem data não pode ser a única coisa que impede a cura.</p>
 *
 * <p><b>O que ela não faz.</b> Não apaga a linha (vira {@code GONE}: o histórico mostra que houve
 * uma tentativa), não apaga corpo de nó nenhum, não designa a cópia nova e não confirma nada. Quem
 * designa de novo é o {@code /storage/heal}; quem grava é o nó. Uma confirmação que chegue depois da
 * soltura é recusada com {@code 409 STORAGE_REPLICA_ALREADY_CONFIRMED} — o catálogo já não conta
 * com aquele nó, e aceitá-la daria uma cópia que o operador não pediu mais.</p>
 */
@Service
public class StoragePendingRelease {

	/** Teto por chamada: o relatório é para ser lido, não é varredura de catálogo inteiro. */
	static final int MAX_RELEASES = 200;

	/**
	 * Uma designação pendente. {@code bucket}/{@code key} são nulos quando o objeto já não existe no
	 * catálogo; {@code ageMinutes} é nulo quando a linha não traz o instante da designação (e nesse
	 * caso ela conta como vencida).
	 */
	public record Pending(String objectId, String bucket, String key, int replica, String nodeId,
			Instant designatedAt, Long ageMinutes, boolean expired) {
	}

	/**
	 * O que está pendente (leitura) ou o que foi solto (escrita). {@code pending} é o mesmo tipo nos
	 * dois casos: a lista que o operador vê é exatamente a que a rota de soltura examina.
	 */
	public record Report(boolean dryRun, int maxAgeMinutes, int limit, List<Pending> pending) {
	}

	/**
	 * Mais antiga primeiro, com desempate estável por objeto e réplica — o mesmo critério do SQL
	 * ({@code order by verified_at, object_id, replica}), repetido aqui para o fake dos testes não
	 * decidir a ordem por acaso de inserção.
	 */
	private static final Comparator<StoragePlacement> MAIS_ANTIGA_PRIMEIRO =
		Comparator.comparing(StoragePlacement::verifiedAt,
			Comparator.nullsFirst(Comparator.naturalOrder()))
			.thenComparing(StoragePlacement::objectId)
			.thenComparingInt(StoragePlacement::replica);

	private final StorageCatalogRepository catalog;
	private final AuditTrail audit;
	private final Clock clock;
	private final int defaultMaxAgeMinutes;

	StoragePendingRelease(StorageCatalogRepository catalog, AuditTrail audit, Clock clock,
			@Value("${rede.storage.replication.pending-max-age-minutes:60}") int defaultMaxAgeMinutes) {
		this.catalog = catalog;
		this.audit = audit;
		this.clock = clock;
		// Zero ou negativo venceria a designação no instante em que nasce. O piso é um minuto, o mesmo
		// do worker de antes.
		this.defaultMaxAgeMinutes = Math.max(1, defaultMaxAgeMinutes);
	}

	/**
	 * Todas as pendências, da mais antiga para a mais nova, com idade e se já passaram do prazo. É a
	 * leitura do operador: mostra o que está a caminho e o que já devia ter chegado.
	 */
	public Report pending(int limit) {
		Instant now = clock.instant();
		Instant limite = now.minus(Duration.ofMinutes(defaultMaxAgeMinutes));
		int teto = teto(limit);
		List<Pending> linhas = catalog.placementsInState(StoragePlacement.State.PENDING_REMOTE).stream()
			.sorted(MAIS_ANTIGA_PRIMEIRO)
			.limit(teto)
			.map(placement -> descrever(placement, now, limite))
			.toList();
		return new Report(true, defaultMaxAgeMinutes, teto, linhas);
	}

	/**
	 * Solta as pendências vencidas. {@code dryRun} mostra o que soltaria; a rota usa o padrão do
	 * módulo (seco primeiro). Cada linha solta é auditada com o nó, a réplica e a idade — a mesma
	 * informação que o operador viu antes de decidir.
	 */
	@Transactional
	public Report releaseExpired(String actorId, Integer maxAgeMinutes, int limit, boolean dryRun) {
		int prazo = maxAgeMinutes == null ? defaultMaxAgeMinutes : Math.max(1, maxAgeMinutes);
		int teto = teto(limit);
		Instant now = clock.instant();
		Instant limite = now.minus(Duration.ofMinutes(prazo));
		List<StoragePlacement> vencidas = catalog.placementsInState(StoragePlacement.State.PENDING_REMOTE)
			.stream()
			.filter(placement -> placement.verifiedAt() == null
				|| !placement.verifiedAt().isAfter(limite))
			.sorted(MAIS_ANTIGA_PRIMEIRO)
			.limit(teto)
			.toList();

		List<Pending> soltas = new ArrayList<>();
		for (StoragePlacement placement : vencidas) {
			Pending linha = descrever(placement, now, limite);
			if (!dryRun) {
				// GONE, não apagada: a linha continua contando a tentativa, e deixa de contar como
				// ocupação e como dono da lacuna. O corpo no nó (se existir) não é tocado: quem apaga
				// arquivo de participante é a poda dele, não o Control Plane.
				catalog.savePlacement(new StoragePlacement(placement.objectId(), placement.replica(),
					placement.nodeId(), placement.bytes(), placement.sha256(), StoragePlacement.State.GONE, now));
				audit.record(actorId, "STORAGE_PENDING_RELEASED", placement.objectId(),
					"{\"node\":\"" + escape(placement.nodeId()) + "\",\"replica\":" + placement.replica()
						+ ",\"ageMinutes\":" + (linha.ageMinutes() == null ? "null" : linha.ageMinutes())
						+ ",\"maxAgeMinutes\":" + prazo + "}");
			}
			soltas.add(linha);
		}
		return new Report(dryRun, prazo, teto, List.copyOf(soltas));
	}

	private Pending descrever(StoragePlacement placement, Instant now, Instant limite) {
		StorageObject objeto = catalog.findById(placement.objectId()).orElse(null);
		Long idade = placement.verifiedAt() == null ? null
			: Duration.between(placement.verifiedAt(), now).toMinutes();
		boolean vencida = placement.verifiedAt() == null || !placement.verifiedAt().isAfter(limite);
		return new Pending(placement.objectId(), objeto == null ? null : objeto.bucket(),
			objeto == null ? null : objeto.key(), placement.replica(), placement.nodeId(),
			placement.verifiedAt(), idade, vencida);
	}

	private static int teto(int limit) {
		return Math.max(1, Math.min(limit <= 0 ? MAX_RELEASES : limit, MAX_RELEASES));
	}

	/** O nó vem do cadastro, mas o JSON de auditoria não depende de o id ser bem formado. */
	private static String escape(String valor) {
		return valor == null ? "" : valor.replace("\\", "\\\\").replace("\"", "\\\"");
	}
}