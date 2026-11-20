package com.socialcoisa.rede.network.application;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.socialcoisa.rede.network.application.port.out.NetworkNodeRepository;
import com.socialcoisa.rede.network.application.port.out.StorageCatalogRepository;
import com.socialcoisa.rede.network.domain.NetworkNode;
import com.socialcoisa.rede.network.domain.NetworkRegions;
import com.socialcoisa.rede.network.domain.NodeProfile;
import com.socialcoisa.rede.network.domain.NodeStatus;
import com.socialcoisa.rede.network.domain.ObjectClass;
import com.socialcoisa.rede.network.domain.StorageNodeKind;
import com.socialcoisa.rede.network.domain.StoragePlacement;
import com.socialcoisa.rede.network.domain.TaskType;

/**
 * Escolha do nó que recebe uma cópia do objeto.
 *
 * <p>A fase 1 gravou sempre no Control Plane (réplica 0). Esta fase acrescenta as réplicas 1..N-1
 * num nó de armazenamento real, e as regras de escolha são as que decidem se uma réplica <b>existe
 * de fato</b>:</p>
 *
 * <ul>
 * <li><b>Não replica no mesmo nó.</b> Duas cópias no mesmo disco não sobrevivem à perda do disco,
 * e a contagem diria 2. Excluir pelo {@code nodeId} é a garantia que a E/S do brief pede sob o
 * nome de "domínio de falha"; o domínio <b>maior</b> que o nó (host físico, operadora, rack) não
 * está cadastrado em lugar nenhum e inventá-lo seria deduzir.</li>
 * <li><b>Diversifica por região enquanto houver região distinta.</b> É o único domínio maior que o
 * nó que o cadastro <b>tem</b> e que o sistema já usa para decidir (evacuação, afinidade). Duas
 * cópias na mesma região caem juntas quando a região cai — o mesmo erro do nó repetido, um nível
 * acima. Região não declarada é domínio desconhecido: não é igual à de quem declarou, nem de outro
 * que também não declarou (a regra de {@code NetworkRegions}).</li>
 * <li><b>Não replica em nó que não está pronto agora.</b> Mesma régua do despacho de tarefa:
 * {@code ACTIVE}, heartbeat recente, perfil de armazenamento, sem quarentena. Nó que o heartbeat
 * ainda não confirmou pode estar com o processo morto.</li>
 * <li><b>Só replica o que não é conhecido como cheio.</b> A folga que decide é a <b>que o
 * participante cedeu</b> (o declarado, menos o que o Énóis já confirmou ocupar neste nó), e não o
 * disco livre da máquina — ver {@link #cedentHeadroomMb}. Sem declarado isso não está medido, e
 * não saber não é saber que não cabe: a web passa, e o {@code store} recusa com o número.</li>
 * <li><b>A escolhe o nó com mais folga cedida</b>. Ordem estável, sem sorteio: o mesmo objeto,
 * reenviado, escolhe o mesmo conjunto enquanto a rede não mudar — e um conjunto que muda a cada
 * retry daria réplicas espalhadas e córpias órfãs. Empate é desfeito pelo id, para a ordem não
 * depender da ordem de chegada da consulta.</li>
 * </ul>
 *
 * <p>O nó escolhido não é "aqui está o IP": ele é designado <b>no catálogo</b>, e recebe o objeto
 * buscando na própria URL do Control Plane, autenticado com a chave de nó. É o mesmo endereçamento
 * que o brief §17 permite — nenhum IP ou hostname de storage node é cadastrado em lugar nenhum.</p>
 */
@Service
public class StoragePlacementService {

	/** Teto da listagem de candidatos por seleção: o mesmo das demais escolhas do módulo. */
	private static final int MAX_CANDIDATES = 100;

	/** Quantas cópias o objeto deve ter no total, contando a do Control Plane (réplica 0). */
	private final int replicas;

	/** Janela de frescor do heartbeat, em segundos. */
	private final java.time.Duration nodeStaleAfter;

	/**
	 * A partir de que idade um objeto conta como arquivo (brief §6, {@code STORAGE_NODE_ARCHIVE}).
	 *
	 * <p>Trinta dias é o mínimo que separa "recente" de "parado" sem exagerar: conteúdo de
	 * aplicação (avatar, thumb, anexo) costuma ser lido no mês. O valor é configuração porque é
	 * decisão de produto, e a colocação só <b>prefere</b> — não exige.
	 */
	private final long coldAfterSeconds;

	private final NetworkNodeRepository nodes;
	private final StorageCatalogRepository catalog;

	// Há dois construtores (o principal e o de teste, abaixo): sem a anotação o Spring não escolhe
	// e tenta o sem-args. Só o principal existe no contexto.
	@org.springframework.beans.factory.annotation.Autowired
	StoragePlacementService(NetworkNodeRepository nodes, StorageCatalogRepository catalog,
			@Value("${rede.storage.replicas:2}") int replicas,
			@Value("${rede.network.node-stale-seconds:90}") int staleSeconds,
			@Value("${rede.storage.cold-after-days:30}") int coldAfterDays) {
		// Uma réplica é o comportamento da fase 1: sem cópia por nó, só a do Control Plane. Zero ou
		// negativo não faz sentido — objeto sem nenhuma cópia não é objeto —, então o piso é 1.
		this.replicas = Math.max(1, replicas);
		this.nodeStaleAfter = java.time.Duration.ofSeconds(Math.max(1, staleSeconds));
		this.nodes = nodes;
		this.catalog = catalog;
		this.coldAfterSeconds = Math.max(1, coldAfterDays) * 86_400L;
	}

	/** Mesma montagem com a janela de arquivo padrão — para quem não configura o módulo. */
	StoragePlacementService(NetworkNodeRepository nodes, StorageCatalogRepository catalog, int replicas,
			int staleSeconds) {
		this(nodes, catalog, replicas, staleSeconds, 30);
	}

	/** O número de cópias configurado, incluindo a do Control Plane. */
	public int targetReplicas() {
		return replicas;
	}

	/**
	 * Nós que devem receber cópias novas deste objeto, em ordem de folga — no máximo
	 * {@code targetReplicas - 1} (o Control Plane é a réplica 0).
	 *
	 * <p>Já têm cópia os nós que aparecem como {@code OK} na colocação: nó que perdeu a cópia
	 * ({@code STALE}/{@code GONE}) volta a ser candidato, que é o caso da retomada.</p>
	 */
	public List<NetworkNode> chooseReplicas(String objectId, long bytes, Instant now) {
		int needed = replicas - 1;
		if (needed <= 0) {
			return List.of();
		}
		var occupied = catalog.placements(objectId)
			.stream()
			.filter(placement -> placement.state() == StoragePlacement.State.OK)
			.map(StoragePlacement::nodeId)
			.toList();

		// Domínios que já têm cópia: nó ocupado e, para a região, a região dos nós ocupados. Só
		// conta a região de nós que o repositório devolveu — a colocação não guarda região, e ler
		// dela exigiria uma consulta por réplica.
		var allCandidates = nodes.findCandidates(now.minus(nodeStaleAfter), MAX_CANDIDATES);
		// A região já ocupada tem de ser lida dos nós que TÊM cópia -- antes de filtrá-los fora.
		// Filtrar primeiro deixava `takenRegions` vazio e a segunda cópia caía na mesma região.
		var takenRegions = allCandidates.stream()
			.filter(node -> occupied.contains(node.id()))
			.map(NetworkNode::region)
			.filter(region -> region != null && !region.isBlank())
			.map(NetworkRegions::normalize)
			.collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
		var ready = allCandidates.stream()
			.filter(node -> !occupied.contains(node.id()))
			.filter(node -> storageReady(node, bytes, now))
			.sorted(preference(objectId, now)
				.thenComparing(Comparator.comparingLong(
					(NetworkNode node) -> cedentHeadroomMbOrZero(node, now) - (bytes / (1024 * 1024)))
					.reversed())
				.thenComparing(NetworkNode::id))
			.toList();

		// Duas passadas sobre a MESMA ordem (mais folga primeiro): primeiro uma cópia por região
		// ainda não usada; se faltar nó, completa com as regiões repetidas. Recusar aqui deixaria o
		// objeto com menos redundância do que a rede permite por causa da topologia — a segunda
		// cópia na mesma região ainda é melhor que nenhuma.
		List<NetworkNode> chosen = new java.util.ArrayList<>(needed);
		for (NetworkNode node : ready) {
			if (chosen.size() >= needed) {
				break;
			}
			String region = domainOf(node);
			if (region.isEmpty() || !takenRegions.contains(region)) {
				chosen.add(node);
				if (!region.isEmpty()) {
					takenRegions.add(region);
				}
			}
		}
		if (chosen.size() < needed) {
			var picked = chosen.stream().map(NetworkNode::id).collect(java.util.stream.Collectors.toSet());
			for (NetworkNode node : ready) {
				if (chosen.size() >= needed) {
					break;
				}
				if (!picked.contains(node.id())) {
					chosen.add(node);
					picked.add(node.id());
				}
			}
		}
		return List.copyOf(chosen);
	}

	/**
	 * O domínio de falha de um nó, na forma comparável. Vazio = desconhecido: quem não declarou
	 * região não é igual a ninguém — nem a outro que também não declarou, nem a uma região
	 * declarada. Tratar dois vazios como "a mesma região" esconderia justamente o caso em que não
	 * há informação para afirmar que são distintos.
	 */
	private static String domainOf(NetworkNode node) {
		return NetworkRegions.normalize(node.region());
	}

	/** O primeiro nó que deve receber cópia nova, se houver. */
	public Optional<NetworkNode> chooseReplica(String objectId, long bytes, Instant now) {
		return chooseReplicas(objectId, bytes, now).stream().findFirst();
	}

	/**
	 * A preferência de tipo de nó por objeto (brief §6): mídia para nó de mídia, objeto parado para
	 * arquivo, o resto para o convencional.
	 *
	 * <p>É <b>preferência</b>, nunca filtro: sem nó do tipo preferido, qualquer nó de armazenamento
	 * recebe a cópia. Exigir o tipo deixaria objeto sem réplica por falta de um perfil que é
	 * declarativo, e a durabilidade que o §9 já mede cairia por causa de topologia.</p>
	 *
	 * <p>Nó que tem <b>vários</b> tipos casa com qualquer um deles. Nó só com {@code NODE_STORAGE}
	 * casa sempre — é o tipo convencional.</p>
	 */
	private Comparator<NetworkNode> preference(String objectId, Instant now) {
		ObjectClass objectClass = objectClass(objectId, now);
		StorageNodeKind wanted = objectClass.preferredKind();
		// Menos primeiro: `false` (0) vem antes de `true` (1). Assim "não casa" perde para "casa".
		return Comparator.comparing((NetworkNode node) -> !kindsOf(node).contains(wanted));
	}

	/** Os tipos de storage que este nó declara. Vazio = não é nó de armazenamento. */
	private static java.util.Set<StorageNodeKind> kindsOf(NetworkNode node) {
		return node.profiles().stream()
			.map(NodeProfile::storageKind)
			.filter(kind -> kind != null)
			.collect(java.util.stream.Collectors.toUnmodifiableSet());
	}

	/** A classe do objeto pelo que o catálogo diz dele; desconhecido cai em DEFAULT. */
	private ObjectClass objectClass(String objectId, Instant now) {
		var object = catalog.findById(objectId).orElse(null);
		if (object == null) {
			return ObjectClass.DEFAULT;
		}
		Long age = object.createdAt() == null ? null
			: java.time.Duration.between(object.createdAt(), now).getSeconds();
		return ObjectClass.of(object.contentType(), age, coldAfterSeconds);
	}

	/**
	 * O nó pode receber a cópia <b>agora</b>: está pronto, tem o perfil, não está em quarentena e
	 * não é conhecido como sem espaço. A ordem dos filtros é a do despacho de tarefa, para que uma
	 * recusa aqui signifique o mesmo que significaria lá.
	 */
	/**
	 * A folga que a colocação promete e a gravação honra: cada designação <b>pendente</b> deste nó
	 * reserva o tamanho dela antes de existir cópia confirmada.
	 *
	 * <p><b>Antes:</b> só a cópia {@code OK} contava como ocupada. Três objetos de 100 GB designados
	 * no mesmo minuto para um nó que cedeu 150 GB passavam os três na colocação: o primeiro seria
	 * gravado, o segundo já estouraria a folga — e o operador descobriria pelo 507 do {@code store},
	 * com o objeto já designado. O objeto que o nó "nunca vai poder" receber tinha sido colocado nele.</p>
	 *
	 * <p><b>Agora:</b> {@code PENDING_REMOTE} reserva. {@code STALE}, {@code GONE} e a designação
	 * vencida não reservam — a primeira não é espaço aproveitável e as outras a coleta já devolve.
	 * É reserva, não promessa: quem não confirma libera na próxima chamada de quem for designar.</p>
	 */
	public long reservadoEmMb(String nodeId) {
		long reservadoMb = 0;
		var copias = catalog.copiesOnNode(nodeId);
		for (StoragePlacement placement : copias) {
			if (placement.state() == StoragePlacement.State.PENDING_REMOTE) {
				reservadoMb += (placement.bytes() / (1024 * 1024)) + 1;
			}
		}
		return reservadoMb;
	}

	private boolean storageReady(NetworkNode node, long bytes, Instant now) {
		return node.status() == NodeStatus.ACTIVE
			&& node.alive(now, nodeStaleAfter)
			&& !node.quarantined(now)
			&& TaskType.OBJECT_PUT.allowedFor(node.profiles())
			&& fitsDisk(node, bytes, now);
	}

	/**
	 * O nó ainda tem espaço <b>na parte que cedeu</b> para esta cópia, com o mesmo piso de folga
	 * que {@code StorageCatalogService} aplica ao gravar.
	 *
	 * <p>Antes: a checagem era o <b>disco livre medido</b> da máquina. Para um participante de
	 * 2 TB que cedeu 1,5 TB isso nunca barra: o disco livre cai até 1,5 TB ocupados e ainda mede
	 * 500 GB, então a colocação continuava mandando cópia — e o objeto ia para além do que a pessoa
	 * autorizou. O livre conta o uso alheio ao Énóis; quem responde "posso ocupar isto aqui" é o
	 * teto cedido.</p>
	 *
	 * <p>Agora: {@code declarado - ocupado} (ver {@link #cedentHeadroomMb}). Declarado ausente não
	 * é medido, e não medido não veta — o {@code store} recusa com o número na hora da web.</p>
	 */
	private boolean fitsDisk(NetworkNode node, long bytes, Instant now) {
		Long headroom = cedentHeadroomMb(node, now);
		// Sem teto cedido não há folga a afirmar: antes da mudança de régua este caso passava
		// ("não medir não é saber que não cabe"), e o resultado era um nó recebendo cópia sem limite
		// nenhum. O §7 proíbe consumir além do limite, e sem o número não existe limite.
		if (headroom == null) {
			return false;
		}
		long neededMb = (bytes / (1024 * 1024)) + 1;
		// As duas réguas: a cedida (o §7/§8 — não passar do que a pessoa autorizou) e o disco livre
		// medido (o arquivo tem de caber de fato; ceder 50 GB num disco com 500 MB não é espaço).
		Long free = node.observedDiskFreeMb();
		if (free != null && free - neededMb < StorageCatalogService.MIN_DISK_FREE_MB) {
			return false;
		}
		return headroom - neededMb >= StorageCatalogService.MIN_DISK_FREE_MB;
	}

	/**
	 * Folga<b>cedida</b> do nó, em MB: o declarado menos o que o Énóis já confirmou ocupar ali.
	 *
	 * <p>Estas duas contas respondem perguntas diferentes e as duas importam:</p>
	 * <ul>
	 * <li><b>ocupado</b> — a soma das cópias {@code OK} deste nó no catálogo. É o que o Énóis
	 * confirmou ter ali; cópia {@code STALE}/{@code GONE} não conta porque não é espaço ocupado que
	 * se possa afirmar;</li>
	 * <li><b>usado no disco</b> — o {@code observedContentUsedMb} que o nó mediu em si (V344). Pega
	 * arquivo que ficou no volume sem estar confirmado no catálogo (cópia interrompida, sobra de
	 * uma troca).</li>
	 * </ul>
	 * <p>O maior dos dois é o ocupado: os dois são limites <b>de baixo</b> do uso real, e usar o
	 * menor esconderia o espaço que um deles já viu estar ocupado.</p>
	 *
	 * <p>{@code null} quando o declarado não foi informado: sem o teto não há folga a calcular, e
	 * {@code 0} diria "não tem espaço" a quem só não declarou. Zero declarado é uma resposta, não
	 * uma ausência — e vale zero de folga.</p>
	 */
	/**
	 * Folga cedida do nó, em MB — a mesma conta que a designação usa, agora legível para quem
	 * <b>confirma</b> a cópia.
	 *
	 * <p>A confirmação só provava que a colocação foi designada e que o SHA confere. Um nó que
	 * recebeu a designação e depois teve o cede reduzido ainda confirmava, e o catálogo passava a
	 * afirmar durabilidade numa cópia fora do limite que o participante definiu. A colocação
	 * {@code PENDING_REMOTE} que está sendo confirmada já entra na conta ({@link #reservadoEmMb}),
	 * então a folga calculada aqui é a que sobra <b>para esta</b> cópia: {@code >= 0} significa que
	 * ela cabe no que o nó cedeu.</p>
	 *
	 * @return {@code null} quando o nó não declarou cede — sem teto não há folga a calcular
	 */
	public Long cedentHeadroomMb(NetworkNode node, Instant now) {
		if (node.declaredDiskMb() <= 0) {
			return null;
		}
		long confirmadoMb = 0;
		for (StoragePlacement placement : catalog.placementsOnNode(node.id())) {
			if (placement.state() == StoragePlacement.State.OK) {
				confirmadoMb += (placement.bytes() / (1024 * 1024)) + 1;
			}
		}
		long medidoMb = node.observedContentUsedMb() == null ? 0 : node.observedContentUsedMb();
		// A designação pendente já é espaço prometido a um objeto que vai chegar: somar só as
		// confirmações deixaria a colocação repetir o mesmo MB para vários objetos no mesmo lote.
		long ocupadoMb = Math.max(confirmadoMb, medidoMb) + reservadoEmMb(node.id());
		return node.declaredDiskMb() - ocupadoMb;
	}

	/**
	 * Folga cedida em MB, para a ordem. Quem chega aqui já tem teto ({@link #fitsDisk} descartou o
	 * resto), então não há sentinela: a soma é sempre um número.
	 */
	private long cedentHeadroomMbOrZero(NetworkNode node, Instant now) {
		Long headroom = cedentHeadroomMb(node, now);
		return headroom == null ? 0L : headroom;
	}

}