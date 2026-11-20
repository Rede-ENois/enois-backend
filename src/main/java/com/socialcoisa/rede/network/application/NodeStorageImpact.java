package com.socialcoisa.rede.network.application;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.network.application.port.out.NetworkNodeRepository;
import com.socialcoisa.rede.network.application.port.out.StorageCatalogRepository;
import com.socialcoisa.rede.network.domain.NetworkNode;
import com.socialcoisa.rede.network.domain.NodeStatus;
import com.socialcoisa.rede.network.domain.StorageObject;
import com.socialcoisa.rede.network.domain.StoragePlacement;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * O preço de revogar um nó de armazenamento, lido <b>antes</b> do ato (brief §17, "remover nó da
 * rede").
 *
 * <h2>O buraco</h2>
 * <p>Revogar é a única transição de nó que não tem volta: as chaves caem na mesma transação e o
 * {@code approve} recusa o retorno. Em compensação, até o ciclo 73 nada contava o que ele levava
 * junto — um nó de armazenamento carrega cópias de objetos que <b>continuam sendo servidos</b>, e
 * revogá-lo tira durabilidade de todo mundo sem aviso. No pior caso o objeto ficava só com a réplica
 * 0 (o disco do Control Plane) e o operador só descobria na varredura seguinte.</p>
 *
 * <h2>Por que isto é leitura, e não um passo da revogação</h2>
 * <p>Designar a cópia nova é do {@link StorageHealer} e da adoção, e o healing nasce desligado de
 * fábrica (doc 13 §2.2). Fazer a revogação disparar designação seria ligar, por efeito colateral,
 * transferência de corpo pela rede de quem participa — decisão de operador, não de rota. O que falta
 * de verdade é <b>saber antes</b>: quais objetos o nó segura e quais perdem a única cópia de nó.</p>
 *
 * <p>Depois de revogado, a varredura ({@code GET /admin/network/nodes/storage/scrub}) passa a contar
 * essas cópias como <b>fora</b> da durabilidade e aponta a lacuna em {@code onRevokedNode}; o
 * {@code POST /admin/network/nodes/storage/heal} repõe. A ordem certa é: ler o impacto → repor o que
 * importa → revogar.</p>
 */
@Service
public class NodeStorageImpact {

	/** Teto: o relatório é para ser lido, não é varredura. Um nó com mais que isto tem outros problemas. */
	static final int MAX_OBJECTS = 500;

	private final NetworkNodeRepository nodes;
	private final StorageCatalogRepository catalog;

	NodeStorageImpact(NetworkNodeRepository nodes, StorageCatalogRepository catalog) {
		this.nodes = nodes;
		this.catalog = catalog;
	}

	/**
	 * O que sai se este nó for revogado.
	 *
	 * @param nodeId nó a avaliar — não precisa ser de armazenamento (os campos vêm vazios)
	 * @param revokingStatus o status que se pretende aplicar; {@code REVOKED} é o que libera o alerta
	 */
	public record Impact(String nodeId, String status, String plannedStatus, int objectsHeld,
			int objectsLosingLastNodeCopy, List<LosesLastCopy> losesLastCopy, boolean truncated, boolean warning) {

		/** Um objeto que, revogado o nó, fica sem nenhuma cópia de nó — só a réplica 0. */
		public record LosesLastCopy(String objectId, String bucket, String key, int replica, long bytes) {
		}

		/**
		 * {@code true} só quando a revogação de fato aconteceria e há objeto a perder. Campo e não
		 * método: um acessor derivado não entra no JSON, e o painel precisa dele serializado.
		 */
		public static boolean alerta(String status, String plannedStatus, int perdidos) {
			return "REVOKED".equals(plannedStatus) && !"REVOKED".equals(status) && perdidos > 0;
		}
	}

	@Transactional(readOnly = true)
	public Impact impact(String nodeId, NodeStatus planned) {
		NetworkNode node = nodes.findById(nodeId)
			.orElseThrow(() -> new ApiException(404, "NODE_NOT_FOUND", "Nó inexistente."));
		List<StoragePlacement> copias = catalog.copiesOnNode(nodeId)
			.stream()
			.filter(p -> p.state() == StoragePlacement.State.OK)
			.toList();
		List<Impact.LosesLastCopy> perdidas = new ArrayList<>();
		boolean truncado = false;
		for (StoragePlacement copia : copias) {
			if (perdidas.size() >= MAX_OBJECTS) {
				truncado = true;
				break;
			}
			StorageObject objeto = catalog.findById(copia.objectId()).orElse(null);
			// Só objeto servível conta: uma lápide cuja única cópia de nó é esta não perde nada que
			// alguém possa baixar.
			if (objeto == null || !objeto.servable()) {
				continue;
			}
			// Quantas cópias de nó OUTRAS este objeto tem. Nó revogado já não serve (as chaves dele
			// caíram), então a saída dele é contada junto com a desta — a pergunta é "depois de
			// revogar, sobra alguma?".
			long outras = catalog.copies(copia.objectId())
				.stream()
				.filter(p -> p.state() == StoragePlacement.State.OK)
				.filter(p -> p.replica() != 0)
				.filter(p -> !p.nodeId().equals(nodeId))
				.filter(p -> nodes.findById(p.nodeId())
					.map(n -> n.status() != NodeStatus.REVOKED)
					.orElse(false))
				.count();
			if (outras == 0) {
				perdidas.add(new Impact.LosesLastCopy(objeto.id(), objeto.bucket(), objeto.key(), copia.replica(),
					copia.bytes()));
			}
		}
		return new Impact(nodeId, node.status().name(), planned.name(), copias.size(), perdidas.size(),
			List.copyOf(perdidas), truncado, Impact.alerta(node.status().name(), planned.name(), perdidas.size()));
	}

}
