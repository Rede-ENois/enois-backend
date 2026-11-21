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
 * <h2>A cópia que não confere também é lacuna</h2>
 * <p>{@code underReplicated} responde "faltam cópias"; {@code divergent} responde "a cópia existe e
 * não é o que o catálogo promete". A segunda não entrava na cura: um corpo trocado no disco de um
 * nó ficava {@code STALE} para sempre, mesmo com a cura rodando a cada rodada, porque a varredura
 * sem releitura não o via. Agora o healer relê o disco desta instalação (o trabalho que ele já faz e
 * é barato) e, para cada cópia divergente, <b>devolve o espaço</b> (a cópia vira {@code GONE}) para a
 * rodada seguinte designar de novo. Também olha as cópias {@code STALE} do catálogo, que a releitura
 * não alcança, e deixa a réplica 0 do Control Plane intacta — ver §5.1.1 do doc 13.</p>

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
			List<Skipped> skipped, List<Object> repostos) {

		public Report(boolean dryRun, int replicasExpected, List<HealedObject> designated, List<Skipped> skipped) {
			this(dryRun, replicasExpected, designated, skipped, List.of());
		}
	}

	/**
	 * O relatório do healing com o despacho ao lado. {@code dispatched} são as tarefas
	 * {@code OBJECT_PUT} enfileiradas; {@code dispatchSkipped} diz por que uma designação não virou
	 * tarefa. Designar sem despachar deixava a cópia esperando um nó que a puxasse por conta própria.
	 */
	public record ReportWithDispatch(Report heal, List<Object> dispatched, List<Object> dispatchSkipped) {
	}

	/**
	 * O que se fez com uma cópia divergente. {@code action}: {@code SERIALIZED} (a cópia virou
	 * {@code GONE} e o espaço será designado de novo) ou {@code REPLICA_ZERO} (o corpo do CP não
	 * confere; não se toca — reler em caminho de cura não pode apagar ou escrever por cima).
	 */
	public record Reposted(String objectId, int replica, String nodeId, String action) {
	}

	/** Digest em JSON: o valor vem de disco, e nunca é reescrito sem escape. */
	private static String escape(String valor) {
		return valor == null ? "" : valor.replace("\\", "\\\\").replace("\"", "\\\"");
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
		// A releitura é só do disco DESTA instalação (réplica 0 e o diretório do nó local): é o único
		// que o Control Plane alcança sem abrir conexão para o participante. É ela que revela a cópia
		// trocada no próprio disco; sem isso, `divergent` só existia no relatório do operador e a cura
		// nunca agia sobre ela.
		StorageScrubber.Report varredura = scrubber.scan(bucket, bounded, true);
		Instant now = clock.instant();
		List<HealedObject> designados = new ArrayList<>();
		List<Skipped> pulados = new ArrayList<>();

		// A divergência vem ANTES da lacuna, e a ordem é o que faz a rodada fechar: a cópia
		// divergente ainda é `OK` no catálogo quando a lacuna é calculada, e o placement a conta como
		// ocupada. Resolver a lacuna primeiro não achava candidato; a divergência virava `GONE` logo
		// depois e o objeto ficava com uma cópia só, sem nada a caminho. Devolvendo o espaço antes, a
		// designação da mesma rodada já pode escolher o nó que estava ocupado.
		List<Object> repostos = new ArrayList<>();
		java.util.Set<String> jaTratadas = new java.util.HashSet<>();
		for (StorageScrubber.Report.Divergent divergente : varredura.divergent()) {
			jaTratadas.add(divergente.objectId() + "#" + divergente.replica());
			repostos.addAll(repor(actorId, divergente, dryRun));
		}
		for (StoragePlacement marcada : copiasStale(bucket, bounded)) {
			if (jaTratadas.contains(marcada.objectId() + "#" + marcada.replica())) {
				continue;
			}
			repostos.addAll(repor(actorId, new StorageScrubber.Report.Divergent(marcada.objectId(),
				marcada.replica(), marcada.nodeId(), marcada.sha256(), ""), dryRun));
		}

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

		LOG.info("storage heal dryRun={} objetos={} designados={} pulados={} repostos={}", dryRun,
			varredura.underReplicated().size(), designados.size(), pulados.size(), repostos.size());
		return new Report(dryRun, varredura.replicasExpected(), List.copyOf(designados), List.copyOf(pulados),
			List.copyOf(repostos));
	}

	/**
	 * As cópias {@code STALE} que existem hoje no catálogo (limitadas ao teto da rodada).
	 *
	 * <p>É o que a varredura sem releitura <b>não</b> vê: as cópias que nunca passaram por este
	 * disco mas foram marcadas quando o nó confirmou outro digest ({@code 409}) ou quando ele mesmo
	 * reportou a divergência. Ler só {@code divergent} deixava a cura sem efeito nesses casos — a
	 * rodada desenhava uma réplica nova a cada vez e as {@code STALE} antigas ficavam para sempre.</p>
	 */
	private List<StoragePlacement> copiasStale(String bucket, int limite) {
		return catalog.placementsInState(StoragePlacement.State.STALE).stream()
			.filter(p -> bucket == null || catalog.findById(p.objectId())
				.map(o -> bucket.equals(o.bucket()))
				.orElse(false))
			.limit(limite)
			.toList();
	}

	/**
	 * A cópia que não confere vira {@code GONE} e o espaço é designado de novo.
	 *
	 * <p><b>Por que {@code GONE} e não {@code STALE}.</b> {@code STALE} diz "o catálogo aponta e o
	 * disco tem outra coisa" — é o estado que a varredura encontra, e ele mantém o arquivo onde
	 * está. A cura não pode apagar o arquivo de um nó (o CP não escreve no disco de participante), e
	 * por isso marca {@code GONE}: o espaço está livre para outra cópia, e o arquivo velho deixa de
	 * contar como espaço ocupado em {@code StoragePlacementService}. O nó limpa o órfão pela própria
	 * poda ({@code /network/storage/inventory/purge}), que já apaga o que o catálogo não atribui a
	 * ele.</p>
	 *
	 * <p><b>Por que o mesmo nó pode ser escolhido.</b> Quando a divergência é a réplica 0 (o disco
	 * do próprio Control Plane), o corpo certo existe no catálogo e é reescrito por quem grava: a
	 * réplica 0 não sai do lugar. Quando é a cópia de um nó local, o arquivo ruim é o que o nó tem,
	 * e a designação nova pede a ele o corpo do CP — mesmo caminho do upload, mesmo digest conferido
	 * na confirmação. A escolha continua sendo de {@link StoragePlacementService}.</p>
	 */
	private List<Object> repor(String actorId, StorageScrubber.Report.Divergent divergente, boolean dryRun) {
		StorageObject objeto = catalog.findById(divergente.objectId()).orElse(null);
		if (objeto == null || !objeto.servable()) {
			// A varredura e a cura disputam o catálogo: entre uma e outra a versão pode ter sido
			// substituída. Não é erro — a cópia divergente da versão antiga não vale mais.
			return List.of();
		}
		// A cópia da réplica 0 é o disco do CP: o corpo certo está aqui e o objeto continua servível
		// por ela. Marcar GONE derrubaria a única cópia que se sabe boa.
		if (divergente.replica() == 0) {
			if (!dryRun) {
				audit.record(actorId, "STORAGE_REPLICA_ZERO_DIVERGENT", objeto.id(),
					"{\"expected\":\"" + escape(divergente.expectedSha256()) + "\",\"actual\":\""
						+ escape(divergente.actualSha256()) + "\"}");
			}
			return List.of(new Reposted(objeto.id(), 0, divergente.nodeId(), "REPLICA_ZERO"));
		}
		List<Object> acoes = new ArrayList<>();
		if (dryRun) {
			acoes.add(new Reposted(objeto.id(), divergente.replica(), divergente.nodeId(), "SERIALIZED"));
			return acoes;
		}
		try {
			tx.executeWithoutResult(semRetorno -> {
				catalog.savePlacement(new StoragePlacement(objeto.id(), divergente.replica(), divergente.nodeId(),
					objeto.bytes() == null ? 0 : objeto.bytes(), objeto.sha256(),
					StoragePlacement.State.GONE, clock.instant()));
				audit.record(actorId, "STORAGE_REPLICA_REPLACED", objeto.id(),
					"{\"node\":\"" + divergente.nodeId() + "\",\"replica\":" + divergente.replica()
						+ ",\"actual\":\"" + escape(divergente.actualSha256()) + "\"}");
			});
			acoes.add(new Reposted(objeto.id(), divergente.replica(), divergente.nodeId(), "SERIALIZED"));
		}
		catch (RuntimeException falha) {
			LOG.warn("storage heal: falha ao repor a copia divergente de {}: {}", objeto.id(), falha.getMessage());
		}
		return acoes;
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