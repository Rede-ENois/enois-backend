package com.socialcoisa.rede.network.application;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.network.application.port.out.NetworkNodeRepository;
import com.socialcoisa.rede.network.application.port.out.NetworkTaskRepository;
import com.socialcoisa.rede.network.domain.DataClass;
import com.socialcoisa.rede.network.domain.NetworkNode;
import com.socialcoisa.rede.network.domain.NetworkTask;
import com.socialcoisa.rede.network.domain.NodeProfile;
import com.socialcoisa.rede.network.domain.TaskType;
import com.socialcoisa.rede.shared.audit.AuditTrail;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Produtor das tarefas de cache de borda (fase 5, {@code NODE_EDGE}).
 *
 * <p>O executor {@code EDGE_CACHE} existe desde a {@code node-runtime.sh}, mas nenhuma tarefa
 * chegava até ele: só {@code MEDIA_TRANSCODE} tinha quem enfileirasse. Um executor sem produtor é
 * código morto — o feed de mudanças não era consumido por ninguém em produção.</p>
 *
 * <p>Aqui há uma diferença que não existe no transcode: o trabalho <b>não é fungível</b>. O cursor e
 * os corpos do cache vivem no disco daquele nó ({@code $NODE_HOME/edge/}), então "aquecer o cache"
 * sem dizer qual nó é pedir que um nó sorteado avance só o próprio cursor enquanto os outros de
 * borda continuam frios. Por isso a tarefa é <b>enfileirada por nó</b>, com alvo, e por isso há
 * dois caminhos:</p>
 *
 * <ul>
 * <li><b>Pedido explícito</b> (rota admin): o admin escolhe o nó. Um nó que acabou de entrar, ou
 * cujo cache foi descartado por {@code reset}, precisa de trabalho antes do próximo ciclo — esperar
 * o ciclo seria deixar a borda fria por tempo indeterminado.</li>
 * <li><b>Aquisição periódica</b> ({@link #sweep()}): todo nó de borda ativo e vivo recebe uma
 * tarefa quando não tem nenhuma em aberto. É o que faz o cache acompanhar o feed sem ninguém
 * apertar botão — e o motivo de a chave de idempotência ser por nó e por janela, não por pedido.</li>
 * </ul>
 *
 * <p>Nada aqui decide autorização de usuário: a tarefa só manda o nó ler o feed público que ele já
 * tem direito de ler pela chave dele.</p>
 */
@Service
public class EdgeCacheService {

	private static final Logger LOG = LoggerFactory.getLogger(EdgeCacheService.class);

	/** Itens por página do feed; o Control Plane corta em 500, e o executor pagina. */
	static final int DEFAULT_LIMIT = 200;

	private final NetworkTaskService tasks;
	private final NetworkTaskRepository taskRepository;
	private final NetworkNodeRepository nodes;
	private final AuditTrail audit;
	private final Clock clock;
	private final int windowMinutes;
	private final int deadlineMinutes;

	EdgeCacheService(NetworkTaskService tasks, NetworkTaskRepository taskRepository, NetworkNodeRepository nodes,
			AuditTrail audit, Clock clock,
			@Value("${rede.network.edge.cache.window-minutes:10}") int windowMinutes,
			@Value("${rede.network.edge.cache.deadline-minutes:30}") int deadlineMinutes) {
		this.tasks = tasks;
		this.taskRepository = taskRepository;
		this.nodes = nodes;
		this.audit = audit;
		this.clock = clock;
		this.windowMinutes = Math.max(1, windowMinutes);
		this.deadlineMinutes = Math.max(1, deadlineMinutes);
	}

	/**
	 * Enfileira a aquisição de cache para um nó escolhido pelo admin.
	 *
	 * <p>Recusa nó inexistente, nó que não é de borda e nó que não pode operar agora. Recusar o nó
	 * errado é diferente de recusar o pedido: o admin vê qual é o problema, e a tarefa não nasce
	 * para um alvo que nunca vai executá-la — o alvo é filtro no despacho, então ela ficaria na fila
	 * até o prazo.</p>
	 */
	@Transactional
	public NetworkTask request(String nodeId, String actorId) {
		if (nodeId == null || nodeId.isBlank()) {
			throw new ApiException(400, "NODE_ID_REQUIRED", "Informe o no.");
		}
		NetworkNode node = nodes.findById(nodeId.trim())
			.orElseThrow(() -> new ApiException(404, "NODE_UNKNOWN", "No nao encontrado."));
		if (!node.profiles().contains(NodeProfile.NODE_EDGE) && !node.profiles().contains(NodeProfile.NODE_LOCAL)) {
			throw new ApiException(422, "NODE_PROFILE_REQUIRED", "No sem perfil de borda nao tem cache a aquecer.");
		}
		if (!node.status().canOperate()) {
			throw new ApiException(409, "NODE_NOT_OPERABLE", "No fora de operacao nao executa tarefa.");
		}
		NetworkTask task = enqueueFor(node, clock.instant(), "admin");
		audit.record(actorId, "NETWORK_EDGE_CACHE_REQUESTED", node.id(),
			"{\"taskId\":\"" + task.id() + "\",\"limit\":" + DEFAULT_LIMIT + "}");
		return task;
	}

	/**
	 * Aquisição periódica: um pedido em aberto por nó de borda vivo.
	 *
	 * <p>A condição de parada é "já tem pedido em aberto". Incrementar sem essa checagem encheria a
	 * fila do nó com uma tarefa a cada ciclo do worker, e o nó executaria as mesmas páginas de novo
	 * depois de a primeira já as ter consumido — o cursor não anda, mas a fila cresce.</p>
	 *
	 * @return quantas tarefas nasceram nesta rodada
	 */
	@Transactional
	public int sweep() {
		Instant now = clock.instant();
		int created = 0;
		for (NetworkNode node : nodes.findCandidates(now.minus(java.time.Duration.ofMinutes(5)), 200)) {
			if (!node.profiles().contains(NodeProfile.NODE_EDGE) && !node.profiles().contains(NodeProfile.NODE_LOCAL)) {
				continue;
			}
			if (taskRepository.countOpenTargeted(node.id(), now) > 0) {
				continue;
			}
			try {
				enqueueFor(node, now, "sweep");
				created++;
			}
			catch (RuntimeException failure) {
				// Um nó não pode parar a rodada: o próximo ciclo tenta de novo para ele.
				LOG.warn("Falha ao enfileirar aquisicao de borda node={}", node.id(), failure);
			}
		}
		if (created > 0) {
			LOG.debug("Aquisicao de borda: {} tarefa(s) enfileirada(s)", created);
		}
		return created;
	}

	/**
	 * Os pedidos em aberto de um nó — o que o painel mostra e o que a aquisição periódica consulta.
	 */
	@Transactional(readOnly = true)
	public List<NetworkTask> openFor(String nodeId) {
		return taskRepository.findPendingTargeted(nodeId, clock.instant(), 20);
	}

	/**
	 * A chave é {@code edge:<nó>:<janela>}. Duas propriedades importam:
	 *
	 * <ul>
	 * <li><b>Por nó</b>, porque dois nós de borda têm cursores diferentes e precisam de tarefas
	 * diferentes. Uma chave global faria o segundo nó receber a tarefa do primeiro (idempotência
	 * devolveria a existente, com alvo do outro nó).</li>
	 * <li><b>Por janela</b>, porque o mesmo nó precisa de trabalho novo depois de consumir o feed.
	 * Sem a janela, a chave seria eterna e a aquisição só aconteceria uma vez na vida do nó.</li>
	 * </ul>
	 *
	 * <p>A janela é de {@code window-minutes} e cabe em 80 caracteres junto com o prefixo (ids de nó
	 * são {@code nd_…} com 40 no máximo). Nó que termina a tarefa e volta na mesma janela não ganha
	 * outra: o {@code countOpenTargeted} já o segurou, e a janela só evita que o <i>mesmo</i>
	 * instante gere duas linhas quando duas instâncias varrem juntas.</p>
	 */
	private NetworkTask enqueueFor(NetworkNode node, Instant now, String origin) {
		String window = Long.toString(now.getEpochSecond() / (windowMinutes * 60L));
		String idempotency = "edge:" + node.id() + ":" + window;
		// `limit` é lido pelo executor; `origin` explica no painel quem pediu. O orçamento de páginas
		// fica no nó (`EDGE_PAGES_PER_TASK`), como o de mudanças por página — mandá-lo aqui sem o
		// executor lê-lo seria contrato mentiroso.
		String payload = "{\"limit\":" + DEFAULT_LIMIT + ",\"origin\":\"" + origin + "\"}";
		Instant deadline = now.plus(deadlineMinutes, ChronoUnit.MINUTES);
		return tasks.enqueue(new NetworkTaskService.EnqueueCommand(TaskType.EDGE_CACHE, idempotency, payload,
			DataClass.PUBLIC, null, deadline, node.id()));
	}
}