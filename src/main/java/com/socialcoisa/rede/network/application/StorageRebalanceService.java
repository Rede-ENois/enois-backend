package com.socialcoisa.rede.network.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.network.application.port.out.StorageCatalogRepository;
import com.socialcoisa.rede.network.domain.StorageObject;
import com.socialcoisa.rede.network.domain.StoragePlacement;
import com.socialcoisa.rede.network.domain.StorageRebalance;
import com.socialcoisa.rede.shared.audit.AuditTrail;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * O rebalanceamento do Storage (brief §16): mover uma réplica de um nó cheio para um com folga.
 *
 * <p><b>A ordem é a regra.</b> O §16 termina com a frase que manda no desenho: <i>nunca remover a
 * última cópia saudável antes de confirmar a persistência e integridade do destino</i>. Por isso a
 * transferência tem três passos, e só o terceiro toca a cópia antiga:</p>
 *
 * <ol>
 * <li><b>designar</b> a réplica nova no destino ({@code PENDING_REMOTE}) e enfileirar o
 * {@code OBJECT_PUT} — a antiga continua {@code OK} e servindo;</li>
 * <li><b>confirmar</b> pelo caminho que já existia ({@code confirmReplica}): o nó declara o que
 * gravou, o Control Plane confere o digest e o tamanho, e divergência marca {@code STALE} e responde
 * 409 —</li>
 * <li><b>só então</b> a cópia antiga vira {@code GONE}, e o objeto fica com a réplica nova.</li>
 * </ol>
 *
 * <p>Consequência: a migração é <b>sem indisponibilidade</b> (a antiga serve durante tudo), e uma
 * transferência que falha no meio não perde nada — o pior resultado é o objeto com uma cópia a mais
 * até a próxima tentativa.</p>
 *
 * <p><b>Rebalancear não é curar.</b> Curar repõe uma cópia que <i>faltou</i> (o objeto está abaixo da
 * redundância). Rebalancear move uma cópia que <i>existe</i>, para aliviar quem tem demais. Por isso
 * a decisão de quem sai vem do relatório de equilíbrio, e o objeto nunca fica com menos réplicas do
 * que tinha: entra a nova antes de sair a antiga.</p>
 */
@Service
public class StorageRebalanceService {

	/** Teto de transferências abertas por chamada: cada uma é tráfego de corpo pela rede de alguém. */
	public static final int MAX_PER_CALL = 20;

	/** Teto de banda padrão de uma transferência, quando o operador não escolhe (Mbps). */
	public static final int DEFAULT_BANDWIDTH_MBPS = 10;

	private final StorageCatalogRepository catalog;
	private final StorageReplicationDispatcher dispatcher;
	private final AuditTrail audit;
	private final Clock clock;

	StorageRebalanceService(StorageCatalogRepository catalog, StorageReplicationDispatcher dispatcher,
			AuditTrail audit, Clock clock) {
		this.catalog = catalog;
		this.dispatcher = dispatcher;
		this.audit = audit;
		this.clock = clock;
	}

	/**
	 * Manda a réplica {@code fromReplica} deste objeto sair do nó atual e nascer no nó
	 * {@code toNodeId}.
	 *
	 * <p><b>O que pode recusar, e por quê:</b></p>
	 * <ul>
	 * <li>a réplica de origem não existe, não é {@code OK}, ou é a <b>única cópia</b> do objeto — mover
	 * a única cópia não é rebalancear, é apostar o arquivo numa transferência;</li>
	 * <li>o destino já tem cópia deste objeto — duas linhas para o mesmo nó não aliviam ninguém e
	 * desviam da conta de domínio de falha;</li>
	 * <li>já existe transferência viva desta réplica (o clique duplo, a retentativa).</li>
	 * </ul>
	 */
	@Transactional
	public StorageRebalance move(String actorId, String objectId, int fromReplica, String toNodeId,
			Integer bandwidthLimitMbps) {
		StorageObject object = catalog.findById(objectId)
			.orElseThrow(() -> new ApiException(404, "STORAGE_NOT_FOUND", "Objeto nao encontrado."));
		if (!object.servable()) {
			throw new ApiException(409, "STORAGE_OBJECT_NOT_SERVABLE", "O objeto nao tem copia conferida.");
		}
		List<StoragePlacement> copias = catalog.copies(objectId);
		StoragePlacement origem = copias.stream()
			.filter(p -> p.replica() == fromReplica)
			.findFirst()
			.orElseThrow(() -> new ApiException(404, "STORAGE_REPLICA_UNKNOWN",
				"O objeto nao tem essa replica."));
		if (origem.state() != StoragePlacement.State.OK) {
			throw new ApiException(409, "STORAGE_REPLICA_NOT_HEALTHY",
				"A replica de origem nao esta saudavel; ela nao e origem de transferencia.");
		}
		// A cópia que sai tem de continuar havendo outra: mover a única é o que o §16 proíbe.
		long sauddaveis = copias.stream().filter(p -> p.state() == StoragePlacement.State.OK).count();
		if (sauddaveis <= 1) {
			throw new ApiException(409, "STORAGE_REBALANCE_LAST_COPY",
				"Esta e a unica copia saudavel do objeto; mover sem outra copia o deixaria sem origem.");
		}
		if (copias.stream().anyMatch(p -> p.nodeId().equals(toNodeId)
				&& p.state() != StoragePlacement.State.GONE)) {
			throw new ApiException(409, "STORAGE_REBALANCE_SAME_NODE",
				"O no de destino ja tem copia deste objeto.");
		}
		var node = dispatcher.node(toNodeId);
		if (node.isEmpty() || !dispatcher.canStore(node.get(), object)) {
			throw new ApiException(409, "STORAGE_REBALANCE_DEST_NOT_READY",
				"O no de destino nao pode receber: precisa estar ativo, com perfil de armazenamento.");
		}
		if (catalog.activeRebalance(objectId, fromReplica).isPresent()) {
			throw new ApiException(409, "STORAGE_REBALANCE_IN_PROGRESS",
				"Ja existe uma transferencia desta replica em andamento.");
		}
		int proxima = copias.stream().mapToInt(StoragePlacement::replica).max().orElse(0) + 1;
		Instant now = clock.instant();
		StorageRebalance rebalance = new StorageRebalance("reb_" + NodeIds.next(""), objectId, fromReplica,
			origem.nodeId(), proxima, toNodeId, StorageRebalance.State.PENDING, null,
			object.bytes() == null ? 0 : object.bytes(),
			bandwidthLimitMbps == null ? DEFAULT_BANDWIDTH_MBPS : bandwidthLimitMbps, actorId, now, null);
		catalog.saveRebalance(rebalance);
		// A cópia nova nasce PENDING_REMOTE: a antiga segue OK e servindo. Se a gravação da designação
		// falhar, a transação volta e nada de transferência ficou.
		catalog.savePlacement(new StoragePlacement(objectId, proxima, toNodeId, rebalance.bytes(),
			object.sha256(), StoragePlacement.State.PENDING_REMOTE, now));
		audit.record(actorId, "STORAGE_REBALANCE_STARTED", objectId,
			"{\"fromReplica\":" + fromReplica + ",\"toReplica\":" + proxima + ",\"node\":\"" + toNodeId
				+ "\",\"bytes\":" + rebalance.bytes() + "}");
		return rebalance;
	}

	/**
	 * Enfileira o {@code OBJECT_PUT} das transferências vivas. Fora da transação do {@link #move} de
	 * propósito: a designação é a obrigação gravada; a tarefa é o que a faz acontecer, e falhar ao
	 * enfileirar não pode desfazer a designação.
	 */
	public List<StorageReplicationDispatcher.Dispatched> dispatch(String objectId) {
		return dispatcher.dispatch(objectId).dispatched();
	}

	/**
	 * Confirma o destino e <b>só então</b> remove a cópia antiga.
	 *
	 * <p>Chamado depois do {@code confirmReplica} que já existe: aqui a pergunta é outra — "a
	 * transferência que estava esperando este nó pode terminar?". Se a réplica nova não estiver
	 * {@code OK}, a antiga fica e a transferência continua; se estiver, a antiga vira {@code GONE}.</p>
	 *
	 * <p>Quando o destino foi removido por uma <b>outra</b> rota (a cópia antiga saiu por lapidação,
	 * por exemplo), a transferência não tem o que fazer: termina como {@code FAILED} com o motivo, em
	 * vez de ficar viva para sempre.</p>
	 */
	@Transactional
	public StorageRebalance complete(String actorId, String rebalanceId) {
		StorageRebalance rebalance = catalog.findRebalance(rebalanceId)
			.orElseThrow(() -> new ApiException(404, "STORAGE_REBALANCE_UNKNOWN", "Transferencia nao encontrada."));
		if (!rebalance.state().active()) {
			throw new ApiException(409, "STORAGE_REBALANCE_FINISHED", "Esta transferencia ja terminou.");
		}
		List<StoragePlacement> copias = catalog.copies(rebalance.objectId());
		StoragePlacement destino = copias.stream()
			.filter(p -> p.replica() == rebalance.toReplica())
			.findFirst()
			.orElse(null);
		if (destino == null || destino.state() != StoragePlacement.State.OK) {
			// Designação sumiu ou ainda não confirmou: a antiga NÃO sai. O operador vê o estado real.
			throw new ApiException(409, "STORAGE_REBALANCE_DEST_NOT_CONFIRMED",
				"O destino ainda nao confirmou a copia; a copia antiga continua no lugar.");
		}
		StoragePlacement origem = copias.stream()
			.filter(p -> p.replica() == rebalance.fromReplica())
			.findFirst()
			.orElse(null);
		long saudaveis = copias.stream().filter(p -> p.state() == StoragePlacement.State.OK).count();
		if (origem != null && origem.state() == StoragePlacement.State.OK && saudaveis <= 1) {
			// Dupla guarda: mesmo com o destino OK, se ele for a única outra cópia, a conta acima já
			// disse que há ao menos duas. A checagem fica porque a lista entre a leitura e a escrita
			// pode ter mudado por outra rota — e a regra não admite exceção.
			throw new ApiException(409, "STORAGE_REBALANCE_LAST_COPY",
				"Remover esta copia deixaria o objeto sem outra copia saudavel.");
		}
		Instant now = clock.instant();
		if (origem != null && origem.state() != StoragePlacement.State.GONE) {
			catalog.savePlacement(new StoragePlacement(origem.objectId(), origem.replica(), origem.nodeId(),
				origem.bytes(), origem.sha256(), StoragePlacement.State.GONE, now));
		}
		catalog.transitionRebalance(rebalanceId, StorageRebalance.State.PENDING, StorageRebalance.State.DONE, null,
			now);
		audit.record(actorId, "STORAGE_REBALANCE_DONE", rebalance.objectId(),
			"{\"fromReplica\":" + rebalance.fromReplica() + ",\"toReplica\":" + rebalance.toReplica()
				+ ",\"node\":\"" + rebalance.toNodeId() + "\"}");
		return catalog.findRebalance(rebalanceId).orElseThrow();
	}

	/** Desfaz uma transferência viva: a cópia nova designada é recolhida e a antiga fica. */
	@Transactional
	public StorageRebalance cancel(String actorId, String rebalanceId, String reason) {
		StorageRebalance rebalance = catalog.findRebalance(rebalanceId)
			.orElseThrow(() -> new ApiException(404, "STORAGE_REBALANCE_UNKNOWN", "Transferencia nao encontrada."));
		Instant now = clock.instant();
		if (!rebalance.state().active()) {
			// Já terminou: desfazer o DONE removeria a cópia nova depois de a antiga ter saído — o
			// exato caminho que deixaria o objeto sem nenhuma das duas.
			throw new ApiException(409, "STORAGE_REBALANCE_FINISHED",
				"Esta transferencia ja terminou; nao ha o que desfazer.");
		}
		catalog.copies(rebalance.objectId()).stream()
			.filter(p -> p.replica() == rebalance.toReplica()
				&& p.state() == StoragePlacement.State.PENDING_REMOTE)
			.findFirst()
			.ifPresent(designada -> catalog.savePlacement(new StoragePlacement(designada.objectId(),
				designada.replica(), designada.nodeId(), designada.bytes(), designada.sha256(),
				StoragePlacement.State.GONE, now)));
		catalog.transitionRebalance(rebalanceId, StorageRebalance.State.PENDING, StorageRebalance.State.CANCELLED,
			recorte(reason), now);
		audit.record(actorId, "STORAGE_REBALANCE_CANCELLED", rebalance.objectId(),
			"{\"reason\":\"" + escape(recorte(reason)) + "\"}");
		return catalog.findRebalance(rebalanceId).orElseThrow();
	}

	/** Transferências vivas: o que o operador pode pausar, retomar (completar) ou desfazer. */
	public List<StorageRebalance> active() {
		return catalog.activeRebalances(MAX_PER_CALL);
	}

	/**
	 * O que a varredura encontra: transferências vivas mas sem designação pendente.
	 *
	 * <p>É a diferença entre "ainda a caminho" e "morreu no meio". A segunda não deve continuar viva:
	 * ou o destino já confirmou (e a {@link #complete} fecha), ou a designação foi recolhida e a
	 * transferência termina como {@code FAILED} com o motivo.</p>
	 */
	public List<StorageRebalance> stale() {
		List<StorageRebalance> orfas = new ArrayList<>();
		for (StorageRebalance rebalance : catalog.activeRebalances(MAX_PER_CALL)) {
			boolean pendente = catalog.copies(rebalance.objectId()).stream()
				.anyMatch(p -> p.replica() == rebalance.toReplica()
					&& p.state() == StoragePlacement.State.PENDING_REMOTE);
			if (!pendente) {
				orfas.add(rebalance);
			}
		}
		return List.copyOf(orfas);
	}

	/**
	 * Solta as transferências órfãs: as que perderam a designação fecham como {@code FAILED}, sem
	 * tocar a cópia antiga.
	 *
	 * <p><b>Por que esta rota passou a existir.</b> {@link #stale()} e {@link #fail} existiam desde o
	 * V354, e <b>nenhum caminho</b> os chamava: a página do painel listava as vivas, o operador via a
	 * transferência de ontem, e não havia ato que a fechasse. Uma designação recolhida por outra rota
	 * (o healing devolveu o espaço, a soltura de pendência venceu) deixava a transferência em
	 * {@code PENDING} para sempre — o doc §12.3 promete {@code FAILED}, e o estado nunca era alcançado.</p>
	 *
	 * <p><b>Por que só seca por padrão.</b> É o mesmo desenho de {@code /storage/pending/release}: a
	 * leitura é segura, a escrita é ato do operador. Fechar é irreversível na direção do {@code DONE}
	 * (transferência {@code FAILED} não completa mais), e quem decide é quem leu a lista.</p>
	 *
	 * <p><b>O que não faz.</b> Não recolhe a designação (quem recolhe é a soltura, com o prazo dela),
	 * não completa, não apaga corpo e não toca a cópia antiga: a transferência só para de esperar.</p>
	 */
	@Transactional
	public Release releaseStale(String actorId, boolean dryRun) {
		List<StorageRebalance> orfas = stale();
		List<StorageRebalance> liberadas = new ArrayList<>();
		if (!dryRun) {
			for (StorageRebalance orfa : orfas) {
				// A varredura e a escrita são transações diferentes: entre uma e outra, a confirmação
				// do destino pode ter chegado e a transferência já não estar viva. `fail` só muda
				// PENDING, e o falso não conta como liberada — é a mesma regra do ato unitário, não
				// uma segunda cópia dela.
				if (fail(actorId, orfa.id(), REASON_STALE)) {
					liberadas.add(orfa);
				}
			}
		}
		return new Release(dryRun, dryRun ? orfas : List.copyOf(liberadas));
	}

	/** Motivo gravado na transferência órfã, na coluna de 120 caracteres. */
	static final String REASON_STALE = "designacao ausente: o destino nao tem mais copia pendente";

	/** O que está órfão (seca) ou o que foi fechado (escrita). */
	public record Release(boolean dryRun, List<StorageRebalance> stale) {
	}

	/** Fecha como {@code FAILED} a transferência que perdeu a designação, sem tocar a cópia antiga. */
	@Transactional
	public boolean fail(String actorId, String rebalanceId, String reason) {
		Instant now = clock.instant();
		boolean mudou = catalog.transitionRebalance(rebalanceId, StorageRebalance.State.PENDING,
			StorageRebalance.State.FAILED, recorte(reason), now);
		if (mudou) {
			audit.record(actorId, "STORAGE_REBALANCE_FAILED", rebalanceId,
				"{\"reason\":\"" + escape(recorte(reason)) + "\"}");
		}
		return mudou;
	}

	/** A coluna de motivo tem 120 caracteres: cortar aqui dá mensagem, estourar dá 500. */
	private static String recorte(String reason) {
		if (reason == null) {
			return null;
		}
		return reason.length() <= 120 ? reason : reason.substring(0, 120);
	}

	private static String escape(String value) {
		return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
	}
}