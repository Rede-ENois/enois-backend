package com.socialcoisa.rede.network.application;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.network.application.port.out.NetworkNodeRepository;
import com.socialcoisa.rede.network.domain.NetworkNode;
import com.socialcoisa.rede.network.domain.NetworkRegions;

/**
 * A capacidade do Storage vista pelos nós: quanto o participante <b>prometeu</b> e quanto ele
 * <b>mediu</b>.
 *
 * <p>São duas coisas e o painel não pode confundi-las:</p>
 * <ul>
 * <li>{@code declaredMb} é o {@code diskMb} do enroll — a promessa, que o runtime impõe como teto;</li>
 * <li>{@code freeMb} é o {@code diskFreeMb} do último heartbeat — o que sobrou de verdade.</li>
 * </ul>
 *
 * <p>{@code freeMb} é {@code null} quando <b>nenhum</b> nó de armazenamento reportou a medida:
 * devolver {@code 0} diria "sem espaço", que é o oposto do desconhecido. {@code unmeasuredNodes}
 * conta quem ficou de fora da soma — um "disponível" que ignora silenciosamente metade dos nós é
 * pior do que um disponível declarado como parcial.</p>
 */
@Service
public class StorageOverviewNodes {

	/** Mesmo "vivo" do escalonador e do painel: quem não fala há tanto tempo não promete disco. */
	private static final long STALE_SECONDS = 180;

	/** Mesmo teto de leitura do painel: agregar além dele mostraria uma rede que a tela não vê. */
	private static final int MAX_LIST = 200;

	public record Capacity(long declaredMb, Long freeMb, int declaringNodes, int unmeasuredNodes) {
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
		for (NetworkNode node : all) {
			// Só nó de armazenamento conta: somar o disco de quem faz transcodificação inflaria a
			// capacidade do Storage com espaço que nenhum objeto pode usar.
			if (!node.profiles().contains(com.socialcoisa.rede.network.domain.NodeProfile.NODE_STORAGE)) {
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
		}
		return new Capacity(declarado, semMedida == comDisco && comDisco > 0 ? null : livre, comDisco, semMedida);
	}
}