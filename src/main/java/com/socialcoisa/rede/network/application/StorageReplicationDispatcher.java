package com.socialcoisa.rede.network.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.network.application.port.out.NetworkNodeRepository;
import com.socialcoisa.rede.network.application.port.out.StorageCatalogRepository;
import com.socialcoisa.rede.network.domain.DataClass;
import com.socialcoisa.rede.network.domain.NetworkNode;
import com.socialcoisa.rede.network.domain.NodeStatus;
import com.socialcoisa.rede.network.domain.StorageObject;
import com.socialcoisa.rede.network.domain.StoragePlacement;
import com.socialcoisa.rede.network.domain.TaskType;

import tools.jackson.databind.ObjectMapper;

/**
 * O <b>produtor</b> de {@code OBJECT_PUT}: transforma uma designação {@code PENDING_REMOTE} numa
 * tarefa que o nó de armazenamento executa.
 *
 * <h2>O buraco que isto fecha</h2>
 * <p>Até aqui a tarefa só existia como <b>tipo</b>: {@code TaskType.OBJECT_PUT} exigia o perfil
 * {@code NODE_STORAGE}, aparecia em {@code allowedTasks} da config assinada, e <b>ninguém
 * enfileirava</b>. O healing designava o nó no catálogo e parava aí — a designação contava com um
 * runtime que soubesse puxar o objeto por conta própria.</p>
 *
 * <p>O runtime é o produtor certo da <b>busca</b> (é ele que tem disco, diretório autorizado e
 * teto de banda), mas a decisão de <b>quem</b> recebe a cópia é do Control Plane — e essa decisão
 * precisa chegar ao nó pelo mesmo caminho de toda tarefa: a fila designada. Sem isso, "designar" e
 * "o nó puxar" eram dois lados que nunca se encontravam.</p>
 *
 * <h2>O payload é o contrato inteiro</h2>
 * <p>{@code objectId} (o que puxar), {@code sha256} e {@code bytes} (contra o que conferir, medidos
 * pelo Control Plane) e {@code replica} (qual linha de colocação a confirmação endereça). O nó não
 * escolhe nada disso — ele lê o corpo em {@code GET /network/storage/objects/{id}} e confirma em
 * {@code POST .../replica}.</p>
 *
 * <p>Uma tarefa por colocação: a chave de idempotência é {@code storage:<objectId>:<replica>}, então
 * pedir de novo a mesma cópia no mesmo nó devolve a mesma tarefa em vez de uma segunda cópia.</p>
 *
 * <h2>A classe de dado é a do objeto, não a da operação</h2>
 * <p>{@code PUBLIC_MEDIA}, a mesma do transcode: o payload leva identidade e medidas de um objeto
 * de armazenamento, e o corpo vai por outra rota. Usar {@code INTERNAL} para dizer "isto é interno
 * do Énóis" seria errado no sentido oposto — a regra dura do módulo (docs 05 §filtro) manda dado
 * que não pode sair da infra para nó {@code NODE_LOCAL}, e o Storage vive num participante
 * {@code NODE_STORAGE}. A tarefa ficaria parada na fila para sempre, sem erro, pelo filtro de
 * classe de dado.</p>
 */
@Service
public class StorageReplicationDispatcher {

	/** Mesmo teto das demais listagens do módulo. */
	private static final int MAX_PLACEMENTS = 100;

	public record Dispatched(String objectId, int replica, String nodeId, String taskId) {
	}

	/** Designações que não viraram tarefa, e por quê. */
	public record Skipped(String objectId, int replica, String reason) {
	}

	public record Report(int considered, List<Dispatched> dispatched, List<Skipped> skipped) {
	}

	private final StorageCatalogRepository catalog;
	private final NetworkNodeRepository nodes;
	private final NetworkTaskService tasks;
	private final ObjectMapper objectMapper;
	private final Clock clock;

	StorageReplicationDispatcher(StorageCatalogRepository catalog, NetworkNodeRepository nodes,
			NetworkTaskService tasks, ObjectMapper objectMapper, Clock clock) {
		this.catalog = catalog;
		this.nodes = nodes;
		this.tasks = tasks;
		this.objectMapper = objectMapper;
		this.clock = clock;
	}

	/** O nó pelo id, para quem decide para onde mover sem ir direto ao repositório. */
	Optional<NetworkNode> node(String nodeId) {
		return nodes.findById(nodeId);
	}

	/**
	 * O nó pode receber <b>este</b> objeto? Ativo, com perfil de armazenamento e objeto servível.
	 * É a mesma condição do {@link #dispatch}: perguntá-la antes de designar evita criar uma
	 * transferência que nunca vai enfileirar tarefa.
	 */
	boolean canStore(NetworkNode node, StorageObject object) {
		return node.status() == NodeStatus.ACTIVE && TaskType.OBJECT_PUT.allowedFor(node.profiles())
			&& object.servable() && object.sha256() != null && object.bytes() != null;
	}

	/** Prazo padrão da tarefa quando ninguém pediu teto: o de sempre, uma hora. */
	static final java.time.Duration PRAZO_PADRAO = java.time.Duration.ofHours(1);

	/**
	 * O prazo da {@code OBJECT_PUT} que leva o objeto ao nó.
	 *
	 * <p><b>Antes:</b> sempre {@code agora + 1 h}. Com teto de banda pedido pelo operador
	 * ({@code bandwidthLimitMbps}) e um objeto grande, o tempo mínimo de transferência passa de uma
	 * hora: a tarefa expirava no meio, o nó perdia o trabalho e a réplica nunca era confirmada — e o
	 * operador repetia a mesma transferência com o mesmo teto sem ligar uma coisa à outra.</p>
	 *
	 * <p><b>Agora:</b> o prazo é o maior entre o padrão e o tempo mínimo no teto (bytes / MB por
	 * segundo, com 1 MB = 1 s a 8 Mbps, o mesmo cálculo do {@code --limit-rate} do nó), com folga de
	 * 20% para o nó ter tempo de conferir o digest. Sem teto declarado não há estimativa honesta — a
	 * banda do nó é que manda — e vale o prazo padrão.</p>
	 */
	static Instant prazo(StorageObject object, Optional<Integer> tetoMbps, Instant now) {
		if (tetoMbps.isEmpty() || object.bytes() == null || object.bytes() <= 0) {
			return now.plus(PRAZO_PADRAO);
		}
		// 1 Mbps = 125 kB/s (o nó aplica `--limit-rate <mbps*125>k`): 8 Mbps movem 1 MiB por segundo.
		// A conta é em bytes reais, não em "MB" decimal — 100 GiB a 8 Mbps leva ~28 h, não 3,5 h.
		long bytesPorSegundo = tetoMbps.get() * 125L * 1024L;
		long segundosMinimos = object.bytes() / bytesPorSegundo;
		java.time.Duration minimo = java.time.Duration.ofSeconds(segundosMinimos + segundosMinimos / 5);
		return now.plus(minimo.compareTo(PRAZO_PADRAO) > 0 ? minimo : PRAZO_PADRAO);
	}

	/**
	 * Enfileira um {@code OBJECT_PUT} para cada designação pendente de um objeto.
	 *
	 * <p>Só {@code PENDING_REMOTE}: {@code OK} já é cópia, {@code STALE}/{@code GONE} é cópia que
	 * existiu e não confere — pedir a essas seria mandar o nó gravar por cima de uma linha que o
	 * healing precisa substituir, não confirmar.</p>
	 */
	@Transactional
	public Report dispatch(String objectId) {
		StorageObject object = catalog.findById(objectId)
			.orElseThrow(() -> new com.socialcoisa.rede.shared.web.ApiException(404, "STORAGE_NOT_FOUND",
				"Objeto nao encontrado."));
		// Pendente é designação, não cópia servível: `placements` devolve só OK e a lista vinha
		// sempre vazia — o despacho nunca mandava gravar a réplica que a fase 3 tinha designado.
		List<StoragePlacement> pendentes = catalog.copies(objectId)
			.stream()
			.filter(p -> p.state() == StoragePlacement.State.PENDING_REMOTE)
			.limit(MAX_PLACEMENTS)
			.toList();
		// Uma consulta das transferências vivas para o lote inteiro, não uma por réplica: o teto de
		// banda é por transferência, e o vínculo com a designação é a réplica de DESTINO.
		List<com.socialcoisa.rede.network.domain.StorageRebalance> transferencias = catalog
			.activeRebalances(MAX_PLACEMENTS);
		Instant now = clock.instant();
		List<Dispatched> enviadas = new java.util.ArrayList<>();
		List<Skipped> puladas = new java.util.ArrayList<>();
		for (StoragePlacement p : pendentes) {
			NetworkNode node = nodes.findById(p.nodeId()).orElse(null);
			// O nó pode ter sido revogado depois da designação: a fila dele não existe mais, e
			// enfileirar seria criar tarefa que nunca roda -- a lacuna continua e ninguém sabe.
			if (node == null || node.status() != com.socialcoisa.rede.network.domain.NodeStatus.ACTIVE
					|| !TaskType.OBJECT_PUT.allowedFor(node.profiles())) {
				puladas.add(new Skipped(objectId, p.replica(), "NODE_NOT_ELIGIBLE"));
				continue;
			}
			if (!object.servable() || object.sha256() == null || object.bytes() == null) {
				puladas.add(new Skipped(objectId, p.replica(), "OBJECT_NOT_SERVABLE"));
				continue;
			}
			String payload;
			// O teto da transferência vale para o payload E para o prazo: é ele que decide quanto
			// tempo o nó leva para mover os bytes.
			Optional<Integer> teto;
			try {
				// O teto da transferência vai junto quando existe uma: sem ele, o
				// `bandwidthLimitMbps` do rebalanceamento era gravado no banco e nunca chegava ao nó,
				// que aplicava só a banda declarada. A transferência é a que DESTINA esta réplica
				// (`toReplica`), não a que sai dela. O nó usa o MENOR entre este e o que declarou.
				teto = transferencias.stream()
					.filter(r -> r.objectId().equals(object.id()) && r.toReplica() == p.replica())
					.map(com.socialcoisa.rede.network.domain.StorageRebalance::bandwidthLimitMbps)
					.filter(mbps -> mbps != null && mbps > 0)
					.findFirst();
				var campos = new java.util.LinkedHashMap<String, Object>();
				campos.put("objectId", object.id());
				campos.put("bucket", object.bucket());
				campos.put("key", object.key());
				campos.put("sha256", object.sha256());
				campos.put("bytes", object.bytes());
				campos.put("replica", p.replica());
				teto.ifPresent(mbps -> campos.put("bandwidthLimitMbps", mbps));
				payload = objectMapper.writeValueAsString(campos);
			}
			catch (RuntimeException serialization) {
				throw new IllegalStateException("Falha ao serializar payload de replica", serialization);
			}
			String idempotency = "storage:" + object.id() + ":" + p.replica();
			var task = tasks.enqueue(new NetworkTaskService.EnqueueCommand(TaskType.OBJECT_PUT, idempotency,
				payload, DataClass.PUBLIC_MEDIA, null, prazo(object, teto, now), node.id()));
			enviadas.add(new Dispatched(objectId, p.replica(), node.id(), task.id()));
		}
		// Designar ao nó é o passo seguinte, e é aqui: sem ele a tarefa nasce PENDING e espera a
		// varredura periódica -- o operador pediu a réplica e nada sairia até o próximo tick.
		// Idempotente por construção (transições condicionais), então repetir não duplica nada.
		if (!enviadas.isEmpty()) {
			tasks.dispatchPending();
		}
		return new Report(pendentes.size(), List.copyOf(enviadas), List.copyOf(puladas));
	}
}