package com.socialcoisa.rede.network.application;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.network.application.port.out.NetworkNodeRepository;
import com.socialcoisa.rede.network.application.port.out.NetworkTaskRepository;
import com.socialcoisa.rede.network.domain.ContentKind;
import com.socialcoisa.rede.network.domain.ContentVersion;
import com.socialcoisa.rede.network.domain.DataClass;
import com.socialcoisa.rede.network.domain.NetworkNode;
import com.socialcoisa.rede.network.domain.NetworkTask;
import com.socialcoisa.rede.network.domain.NodeProfile;
import com.socialcoisa.rede.network.domain.TaskType;
import com.socialcoisa.rede.shared.audit.AuditTrail;
import com.socialcoisa.rede.shared.security.Hashes;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Produtor das tarefas de entrega de borda ({@code EDGE_DELIVER}).
 *
 * <p>Diferença com {@code EDGE_CACHE}: a aquisição pergunta "traga o que mudou"; a entrega
 * pergunta "você consegue entregar <b>este</b> conteúdo agora?". Uma resposta é sobre o feed,
 * a outra sobre um agregado; por isso o payload carrega {@code kind} e {@code contentId}, e não
 * um limite de página.</p>
 *
 * <p>Como o cache, isto é <b>por nó</b> e com alvo: o corpo que se verifica está no disco
 * daquele nó ({@code $NODE_HOME/edge/}), então uma tarefa sem alvo seria designada a um nó
 * sorteado que pode nunca ter baixado o agregado — e o veredicto dele seria sobre outro cache.</p>
 */
@Service
public class EdgeDeliverService {

	private final NetworkTaskService tasks;
	private final NetworkTaskRepository taskRepository;
	private final NetworkNodeRepository nodes;
	private final ContentSyncService content;
	private final AuditTrail audit;
	private final Clock clock;
	private final int windowMinutes;
	private final int deadlineMinutes;

	EdgeDeliverService(NetworkTaskService tasks, NetworkTaskRepository taskRepository, NetworkNodeRepository nodes,
			ContentSyncService content, AuditTrail audit, Clock clock,
			@Value("${rede.network.edge.deliver.window-minutes:5}") int windowMinutes,
			@Value("${rede.network.edge.deliver.deadline-minutes:10}") int deadlineMinutes) {
		this.tasks = tasks;
		this.taskRepository = taskRepository;
		this.nodes = nodes;
		this.content = content;
		this.audit = audit;
		this.clock = clock;
		this.windowMinutes = Math.max(1, windowMinutes);
		this.deadlineMinutes = Math.max(1, deadlineMinutes);
	}

	/**
	 * Enfileira a verificação de entrega de um conteúdo por um nó escolhido pelo admin.
	 *
	 * <p>Recusa nó inexistente, nó sem perfil de borda, nó fora de operação e conteúdo que não
	 * existe — cada um com o próprio código, porque a correção é diferente em cada caso. Recusar
	 * aqui evita a tarefa que nasce fadada: o alvo é filtro no despacho, então ela ficaria na fila
	 * até o prazo sem nunca ser executada.</p>
	 *
	 * <p>O 404 de conteúdo vem da projeção pública, com o código dela ({@code USER_NOT_FOUND} para
	 * perfil, o da comunidade para comunidade). Reescrever para um código genérico esconderia de
	 * quem chamou qual agregado faltou.</p>
	 */
	@Transactional
	public NetworkTask request(String nodeId, String kindRaw, String contentId, String actorId) {
		if (nodeId == null || nodeId.isBlank()) {
			throw new ApiException(400, "NODE_ID_REQUIRED", "Informe o no.");
		}
		if (contentId == null || contentId.isBlank()) {
			throw new ApiException(400, "CONTENT_ID_REQUIRED", "Informe o conteudo.");
		}
		ContentKind kind = ContentKind.parse(kindRaw)
			.orElseThrow(() -> new ApiException(400, "CONTENT_KIND_UNKNOWN", "Tipo de conteudo desconhecido."));
		NetworkNode node = nodes.findById(nodeId.trim())
			.orElseThrow(() -> new ApiException(404, "NODE_UNKNOWN", "No nao encontrado."));
		if (!node.profiles().contains(NodeProfile.NODE_EDGE) && !node.profiles().contains(NodeProfile.NODE_LOCAL)) {
			throw new ApiException(422, "NODE_PROFILE_REQUIRED", "No sem perfil de borda nao entrega conteudo.");
		}
		if (!node.status().canOperate()) {
			throw new ApiException(409, "NODE_NOT_OPERABLE", "No fora de operacao nao executa tarefa.");
		}
		String id = normalize(kind, contentId);
		// Cunha a versão pelo mesmo caminho que a borda lê. Isso faz duas coisas de uma vez: confirma
		// que o agregado existe (perfil inexistente e comunidade inexistente lançam 404 aqui) e deixa a
		// versão vigente pronta para quem for comparar. Cunhar na leitura é o desenho — o que ninguém
		// pede não precisa de versão.
		ContentVersion version = content.publish(kind, id);
		NetworkTask task = enqueueFor(node, kind, id, clock.instant());
		audit.record(actorId, "NETWORK_EDGE_DELIVER_REQUESTED", node.id(),
			"{\"taskId\":\"" + task.id() + "\",\"kind\":\"" + kind.name() + "\",\"contentId\":\"" + id
				+ "\",\"version\":" + version.version() + "}");
		return task;
	}

	/** Os pedidos de entrega em aberto de um nó — o que ainda está na fila ou executando. */
	@Transactional(readOnly = true)
	public List<NetworkTask> openFor(String nodeId) {
		return taskRepository.findPendingTargeted(nodeId, clock.instant(), 20).stream()
			.filter(task -> task.taskType() == TaskType.EDGE_DELIVER)
			.toList();
	}

	/**
	 * A chave é hasheada, não concatenada.
	 *
	 * <p>{@code deliver:<nó>:<tipo>:<id>:<janela>} estouraria a coluna de 80 caracteres: id de nó
	 * tem até 40 e id de conteúdo até 64. O hash mantém a propriedade que importa — a mesma
	 * combinação na mesma janela é a mesma chave, combinações diferentes não colidem — e cabe
	 * sempre. Por isso o prefixo identifica o tipo e o resto é o digest.</p>
	 */
	private NetworkTask enqueueFor(NetworkNode node, ContentKind kind, String contentId, Instant now) {
		String window = Long.toString(now.getEpochSecond() / (windowMinutes * 60L));
		String idempotency = "deliver:" + Hashes.sha256Hex(node.id() + ":" + kind.name() + ":" + contentId + ":"
			+ window);
		// `kind` e `contentId` são lidos pelo executor; nada além disso é mandado, porque campo que o
		// executor não lê seria contrato mentiroso.
		String payload = "{\"kind\":\"" + kind.name() + "\",\"contentId\":\"" + escape(contentId) + "\"}";
		Instant deadline = now.plus(deadlineMinutes, ChronoUnit.MINUTES);
		return tasks.enqueue(new NetworkTaskService.EnqueueCommand(TaskType.EDGE_DELIVER, idempotency, payload,
			DataClass.PUBLIC, null, deadline, node.id()));
	}

	/** O @ é comparado sem caixa, como no cache público; o id da comunidade é literal. */
	private static String normalize(ContentKind kind, String contentId) {
		String trimmed = contentId.trim();
		return kind == ContentKind.PROFILE ? trimmed.toLowerCase(java.util.Locale.ROOT) : trimmed;
	}

	private static String escape(String text) {
		return text.replace("\\", "\\\\").replace("\"", "\\\"");
	}
}