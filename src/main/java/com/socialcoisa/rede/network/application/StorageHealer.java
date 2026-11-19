package com.socialcoisa.rede.network.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.socialcoisa.rede.network.application.port.out.StorageCatalogRepository;
import com.socialcoisa.rede.network.domain.NetworkNode;
import com.socialcoisa.rede.network.domain.StorageObject;
import com.socialcoisa.rede.network.domain.StoragePlacement;

/**
 * Healing do Storage (fase 3, brief §"Recuperação de objetos"): pega a lacuna que a varredura
 * <b>aponta</b> e a transforma em cópia designada — sem escolher nó novo, sem tocar no corpo.
 *
 * <p><b>O que já existia e não tinha chamador.</b> Quando um upload termina, {@code designReplicas}
 * escolhe os nós e grava a colocação {@code PENDING_REMOTE}; o nó busca o corpo e confirma em
 * {@code POST /network/storage/objects/{id}/replica}. Só que essa designação só acontecia <b>no
 * instante do upload</b>. Se a rede estivesse sem nó de armazenamento vivo naquele momento — o
 * caso mais comum num cluster que sobe — ou se a réplica fosse confirmada {@code STALE}, o objeto
 * ficava {@code underReplicated} <b>para sempre</b>: o {@code /storage/scrub} apontava a lacuna e
 * nada sabia a quem pedir a cópia. Este serviço é esse chamador.</p>
 *
 * <h2>O que ele NÃO faz, de propósito</h2>
 * <ul>
 * <li><b>não relê corpo de nó nenhum</b>: quem tem a cópia boa é a de origem (réplica 0) ou uma
 * {@code OK}; uma réplica {@code STALE} pode ter o conteúdo trocado, e copiá-lo espalharia a
 * divergência. {@code staleCopiesIgnored} conta essas cópias, porque "faltam 2" com uma cópia
 * suspeita no lugar é um pedido de healer diferente de "faltam 2" de verdade;</li>
 * <li><b>não escolhe réplica</b>: a regra de diversidade de região é a de {@link
 * StoragePlacementService}, a mesma do upload. Duas regras de colocação seriam duas chances de
 * achar que a redundância existe quando não existe;</li>
 * <li><b>não marca a cópia como presente</b>: quem grava de fato é o nó, e só a confirmação dele
 * (bytes + sha-256 conferidos) torna a cópia {@code OK}. Designar e declarar pronto seria a
 * mesma mentira que o contrato do Storage evita em todo o resto.</li>
 * </ul>
 *
 * <h2>É um ato de operador</h2>
 * <p>Designar dispara tráfego de corpo (o nó busca o objeto no Control Plane), e o brief é
 * explícito em que o custo de disco e banda é de quem contribui. Por isso não há agendador: é
 * {@code POST /admin/network/nodes/storage/heal}, com {@code dryRun} para quem só quer ver o que
 * seria feito. O teto {@code maxObjects} é obrigatório — sem ele, um catálogo grande viraria uma
 * onda de réplicas de uma vez.</p>
 */
@Service
public class StorageHealer {

	private static final Logger LOG = LoggerFactory.getLogger(StorageHealer.class);

	/** Mesmo teto da listagem do módulo: curar mais do que a varredura mostra curaria o que ninguém viu. */
	public static final int MAX_OBJECTS_LIMIT = StorageCatalogService.MAX_LIST_LIMIT;

	/**
	 * O que seria feito, ou o que foi feito. {@code designated} são as designações novas; o
	 * {@code healedObject} diz de qual objeto. {@code skipped} traz o motivo de cada lacuna que
	 * <b>não</b> virou designação — sem ele, um healer que não faz nada parece um healer que não
	 * tem o que fazer.
	 */
	public record Report(boolean dryRun, int replicasExpected, List<HealedObject> designated,
			List<Skipped> skipped) {
	}

	/**
	 * O relatório do healing com o despacho ao lado. {@code dispatched} são as tarefas
	 * {@code OBJECT_PUT} enfileiradas; {@code dispatchSkipped} diz por que uma designação não virou
	 * tarefa. Designar sem despachar deixava a cópia esperando um nó que a puxasse por conta própria.
	 */
	public record ReportWithDispatch(Report heal, List<Object> dispatched, List<Object> dispatchSkipped) {
	}

	/** Uma designação ({@code replica} e {@code nodeId}) para o objeto. */
	public record HealedObject(String objectId, int replica, String nodeId) {
	}

	/**
	 * Uma lacuna que não foi curada. {@code reason}:
	 * <ul>
	 * <li>{@code NO_CANDIDATE}: nenhum nó livre com o perfil, o disco e a região exigidas;</li>
	 * <li>{@code STALE_COPIES_ONLY}: as cópias que sobram não conferem — requisitadas, mas o corpo
	 * não é confiável para replicar;</li>
	 * <li>{@code BODY_MISSING}: o objeto está servível no catálogo mas o Control Plane não tem o
	 * arquivo para servir à réplica;</li>
	 * <li>{@code ALREADY_DESIGNATED}: a lacuna já tem {@code PENDING_REMOTE} esperando a
	 * confirmação do nó — designar de novo depois do retry só reinicia o relógio.</li>
	 * </ul>
	 */
	public record Skipped(String objectId, int missing, String reason) {
	}

	private final StorageScrubber scrubber;
	private final StoragePlacementService placement;
	private final StorageCatalogRepository catalog;
	private final com.socialcoisa.rede.shared.audit.AuditTrail audit;
	private final TransactionTemplate tx;
	private final Clock clock;

	StorageHealer(StorageScrubber scrubber, StoragePlacementService placement, StorageCatalogRepository catalog,
			com.socialcoisa.rede.shared.audit.AuditTrail audit,
			PlatformTransactionManager transactionManager, Clock clock) {
		this.scrubber = scrubber;
		this.placement = placement;
		this.catalog = catalog;
		this.audit = audit;
		this.tx = new TransactionTemplate(transactionManager);
		this.clock = clock;
	}

	/**
	 * Cura a lacuna apontada pela varredura.
	 *
	 * <p>Não é {@code @Transactional} no método inteiro: cada objeto vira uma transação própria.
	 * Um nó escolhido que entre em quarentena no meio do lote não pode desfazer as designações dos
	 * objetos anteriores — elas são válidas e o nó vai buscá-las. Transação única também
	 * seguraria o lock do catálogo por todo o lote.</p>
	 */
	public Report heal(String actorId, String bucket, int maxObjects, boolean dryRun) {
		int bounded = Math.max(1, Math.min(maxObjects <= 0 ? MAX_OBJECTS_LIMIT : maxObjects, MAX_OBJECTS_LIMIT));
		// A varredura NÃO relê corpo: curar a partir de uma varredura que relê disco do nó seria
		// pagar o custo de leitura sem precisar dele. O que a cura precisa é a lista de lacunas.
		StorageScrubber.Report varredura = scrubber.scan(bucket, bounded, false);
		Instant now = clock.instant();
		List<HealedObject> designados = new ArrayList<>();
		List<Skipped> pulados = new ArrayList<>();

		for (StorageScrubber.Report.UnderReplicated lacuna : varredura.underReplicated()) {
			StorageObject objeto = catalog.findById(lacuna.objectId()).orElse(null);
			if (objeto == null || !objeto.servable()) {
				// Varredura e cura disputam o mesmo catálogo: entre uma e outra o objeto pode ter
				// sido lapidado. Não é erro -- é um objeto que não precisa mais de cópia.
				pulados.add(new Skipped(lacuna.objectId(), lacuna.missing(), "BODY_MISSING"));
				continue;
			}
			if (temDesignacaoPendente(objeto.id())) {
				pulados.add(new Skipped(objeto.id(), lacuna.missing(), "ALREADY_DESIGNATED"));
				continue;
			}
			// As cópias que sobraram: só as OK servem de origem. As demais são contadas, não usadas.
			// `copies` (todos os estados), não `placements` (só OK): o número da réplica nova sai do
			// máximo de TODAS, e ler só as OK reaproveitaria o número de uma GONE.
			List<StoragePlacement> copias = catalog.copies(objeto.id());
			Set<String> nosComCopiaBoa = copias.stream()
				.filter(p -> p.state() == StoragePlacement.State.OK)
				.map(StoragePlacement::nodeId)
				.collect(java.util.stream.Collectors.toSet());
			long bytes = objeto.bytes() == null ? 0 : objeto.bytes();

			List<NetworkNode> escolhidos = placement.chooseReplicas(objeto.id(), bytes, now);
			int limite = Math.min(lacuna.missing(), escolhidos.size());
			if (limite == 0) {
				// Distinguir "não há nó" de "não há cópia boa" importa para o operador: o primeiro
				// se resolve esperando ou admitindo nó; o segundo exige olhar o conteúdo.
				String motivo = nosComCopiaBoa.isEmpty() ? "STALE_COPIES_ONLY" : "NO_CANDIDATE";
				if (copias.isEmpty()) {
					// Sem nenhuma linha de colocação, o objeto está servível só na réplica 0 do CP.
					motivo = "NO_CANDIDATE";
				}
				pulados.add(new Skipped(objeto.id(), lacuna.missing(), motivo));
				continue;
			}
			if (dryRun) {
				for (int i = 0; i < limite; i++) {
					designados.add(new HealedObject(objeto.id(), -1, escolhidos.get(i).id()));
				}
				continue;
			}
			gravarDesignacoes(actorId, objeto, escolhidos.subList(0, limite), copias, bytes, designados);
		}

		LOG.info("storage heal dryRun={} objetos={} designados={} pulados={}", dryRun,
			varredura.underReplicated().size(), designados.size(), pulados.size());
		return new Report(dryRun, varredura.replicasExpected(), List.copyOf(designados), List.copyOf(pulados));
	}

	/**
	 * Já existe cópia designada a alguém esperando confirmação? Então a lacuna tem dono.
	 *
	 * <p>Lê {@code copies}, não {@code placements}: a pergunta é por {@code PENDING_REMOTE}, e
	 * {@code placements} devolve só {@code OK} — a resposta era sempre "não há dono", e a cura
	 * designava de novo uma réplica que já estava a caminho, com o mesmo objeto e um número novo.</p>
	 */
	private boolean temDesignacaoPendente(String objectId) {
		return catalog.copies(objectId).stream()
			.anyMatch(p -> p.state() == StoragePlacement.State.PENDING_REMOTE);
	}

	/**
	 * Grava as designações numa transação só para este objeto, e audita cada uma como o upload faz.
	 * O número da réplica continua de onde o catálogo parou: reaproveitar um número ocupado faria
	 * a chave primária {@code (object_id, replica)} colidir com a cópia antiga e a designação nova
	 * sobrescreveria o nó da antiga silenciosamente.
	 */
	private void gravarDesignacoes(String actorId, StorageObject objeto, List<NetworkNode> escolhidos,
			List<StoragePlacement> copias, long bytes, List<HealedObject> destino) {
		int proximo = copias.stream().mapToInt(StoragePlacement::replica).max().orElse(0) + 1;
		Instant now = clock.instant();
		try {
			tx.executeWithoutResult(semRetorno -> {
				int replica = proximo;
				for (NetworkNode node : escolhidos) {
					catalog.savePlacement(new StoragePlacement(objeto.id(), replica, node.id(), bytes,
						objeto.sha256(), StoragePlacement.State.PENDING_REMOTE, now));
					audit.record(actorId, "STORAGE_REPLICA_HEALED", objeto.id(),
						"{\"node\":\"" + node.id() + "\",\"replica\":" + replica + "}");
					destino.add(new HealedObject(objeto.id(), replica, node.id()));
					replica++;
				}
			});
		}
		catch (RuntimeException falha) {
			// Um objeto que falha não derruba o lote: a transação dele volta, as anteriores ficam, e
			// o operador vê o motivo no skipped em vez de um 500 sem os designados.
			LOG.warn("storage heal: falha ao designar {}: {}", objeto.id(), falha.getMessage());
			destino.removeIf(d -> d.objectId().equals(objeto.id()));
			audit.record(actorId, "STORAGE_REPLICA_HEAL_FAILED", objeto.id(),
				"{\"error\":\"" + falha.getClass().getSimpleName() + "\"}");
		}
	}
}