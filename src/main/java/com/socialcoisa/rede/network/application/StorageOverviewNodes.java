package com.socialcoisa.rede.network.application;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.network.application.port.out.NetworkNodeRepository;
import com.socialcoisa.rede.network.domain.NetworkNode;
import com.socialcoisa.rede.network.domain.NetworkRegions;
import com.socialcoisa.rede.network.domain.TaskType;

/**
 * A capacidade do Storage do que os nós <b>prometem</b> e do que eles <b>medem</b>, mais a conta de
 * reserva de cada volume (brief do Storage §7).
 *
 * <p>Três coisas distintas, que o painel não pode confundir:</p>
 * <ul>
 * <li>{@code declaredMb} é o {@code diskMb} do enroll — a promessa, que o runtime impõe como teto;</li>
 * <li>{@code freeMb} é o {@code diskFreeMb} do último heartbeat — o que sobrou de verdade;</li>
 * <li>o {@code Disco} de cada nó traz o <b>tamanho</b> medido e a <b>reserva</b>, que é
 * {@code total - declarado}: o volume fora do Énóis por decisão do participante.</li>
 * </ul>
 *
 * <p>{@code freeMb} é {@code null} quando <b>nenhum</b> nó de armazenamento reportou a medida:
 * devolver {@code 0} diria "sem espaço", que é o oposto do desconhecido. {@code unmeasuredNodes}
 * conta quem ficou de fora da soma — um "disponível" que ignora silenciosamente metade dos nós é
 * pior do que um disponível declarado como parcial.</p>
 *
 * <p><b>Por que a reserva não é somada:</b> ela é a conta de um volume, e não existe "quantidade de
 * espaço reservado na rede". Um número agregado juntaria o que cada participante guardou para si
 * sem que a soma responda a pergunta nenhuma — nem ao operador (que quer saber se um nó específico
 * cedeu o que prometeu) nem ao participante (que quer ver o próprio disco). Por isso a reserva sai
 * <b>por nó</b>; a soma continua só onde ela tem sentido: declarado e livre.</p>
 */
@Service
public class StorageOverviewNodes {

	/** Mesmo "vivo" do escalonador e do painel: quem não fala há tanto tempo não promete disco. */
	private static final long STALE_SECONDS = 180;

	/** Mesmo teto de leitura do painel: agregar além dele mostraria uma rede que a tela não vê. */
	private static final int MAX_LIST = 200;

	/**
	 * A conta de um volume. {@code totalMb} e {@code reservedMb} são {@code null} quando o nó não
	 * reportou o tamanho do volume (runtime anterior à V341) — nunca {@code 0}, que seria um disco
	 * de tamanho zero.
	 *
	 * @param declaredMb o que o participante cedeu neste nó
	 * @param freeMb o que está livre agora; {@code null} = não medido
	 * @param totalMb o tamanho do volume; {@code null} = não medido
	 * @param reservedMb {@code total - declarado}, nunca negativo; {@code null} = total não medido
	 * @param usedMb o que o ESTADO DE CONTEÚDO ocupa, medido pelo nó; {@code null} = não reportado
	 * @param usedPercent {@code usedMb / declarado} em porcentagem; {@code null} = uso não medido ou
	 * teto zero. Pode passar de 100 — nó acima do teto é o que se quer enxergar
	 * ("limite máximo de utilização" do §7)
	 */
	public record Disco(String nodeId, int declaredMb, Long freeMb, Long totalMb, Long reservedMb,
			Long usedMb, Double usedPercent) {
	}

	/**
	 * @param unmeasuredNodes nós de armazenamento que não reportaram o disco <b>livre</b>
	 * @param unmeasuredUsage nós de armazenamento que não reportaram o <b>uso</b> do conteúdo
	 * @param unmeasuredDiskTotals nós de armazenamento que não reportaram o <b>tamanho</b> do volume.
	 * Podem ser diferentes na mesma rede — e é por isso que são contadores distintos: durante a
	 * atualização dos runtimes, o comum é conhecer o livre e ainda não conhecer o total.
	 */
	public record Capacity(long declaredMb, Long freeMb, int declaringNodes, int unmeasuredNodes,
			int unmeasuredDiskTotals, int unmeasuredUsage, List<Disco> disks) {
	}

	private final NetworkNodeRepository nodes;
	private final NetworkRegionService regions;
	private final Clock clock;

	StorageOverviewNodes(NetworkNodeRepository nodes, NetworkRegionService regions, Clock clock) {
		this.nodes = nodes;
		this.regions = regions;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public Capacity capacity() {
		List<NetworkNode> all = nodes.list(MAX_LIST);
		// Região evacuada é decisão humana: o disco dela não está disponível para objeto novo, e somar
		// prometeria capacidade que o escalonamento já recusa.
		java.util.List<String> evacuadas = regions.outOfService();
		long declarado = 0;
		long livre = 0;
		int comDisco = 0;
		int semMedida = 0;
		int semTotal = 0;
		int semUso = 0;
		List<Disco> discos = new java.util.ArrayList<>();
		for (NetworkNode node : all) {
			// Quem conta é quem PODE guardar objeto -- o mesmo critério do despacho e da colocação
			// (`OBJECT_PUT.allowedFor`), não o nome do perfil. Nó da infraestrutura própria
			// ({@code NODE_LOCAL}) guarda objeto no próprio disco e cobria todos os tipos, mas o
			// filtro por nome de perfil o deixava fora: o painel mostrava menos capacidade do que a
			// rede tem, e o operador decidia colocação sobre um número que excluía as réplicas locais.
			if (!TaskType.OBJECT_PUT.allowedFor(node.profiles())) {
				continue;
			}
			if (!node.alive(clock.instant(), Duration.ofSeconds(STALE_SECONDS))) {
				// Nó parado não promete disco agora: contar o declarado dele somaria capacidade que
				// não está no ar.
				continue;
			}
			if (NetworkRegions.outOfService(evacuadas, node.region())) {
				continue;
			}
			declarado += node.declaredDiskMb();
			comDisco++;
			if (node.observedDiskFreeMb() == null) {
				semMedida++;
			}
			else {
				livre += node.observedDiskFreeMb();
			}
			if (node.observedContentUsedMb() == null) {
				semUso++;
			}
			if (node.observedDiskTotalMb() == null) {
				semTotal++;
			}
			discos.add(diskOf(node));
		}
		Long livreAgregado = semMedida == comDisco && comDisco > 0 ? null : livre;
		return new Capacity(declarado, livreAgregado, comDisco, semMedida, semTotal, semUso, List.copyOf(discos));
	}

	/**
	 * O disco de <b>um</b> nó, para a página individual do §17.
	 *
	 * <p>A mesma conta de {@link #capacity()}, com a mesma fonte: o {@link Disco} é montado pelo
	 * mesmo código que monta a linha do painel geral. Duas contas para o mesmo disco dariam dois
	 * números na tela — e o operador não saberia qual acreditar.</p>
	 *
	 * <p>{@code null} quando o nó não existe ou não pode guardar objeto ({@code OBJECT_PUT}): a
	 * página do nó o mostra, mas chamar de "disco de armazenamento" um nó de processamento
	 * prometeria espaço que ele não oferece.</p>
	 */
	@Transactional(readOnly = true)
	public Disco diskOf(String nodeId) {
		return nodes.findById(nodeId)
			.filter(node -> TaskType.OBJECT_PUT.allowedFor(node.profiles()))
			.map(StorageOverviewNodes::diskOf)
			.orElse(null);
	}

	private static Disco diskOf(NetworkNode node) {
		return new Disco(node.id(), node.declaredDiskMb(), node.observedDiskFreeMb(), node.observedDiskTotalMb(),
			node.reservedDiskMb(), node.observedContentUsedMb(), node.usedPercent());
	}
}