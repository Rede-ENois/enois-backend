package com.socialcoisa.rede.network.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.network.application.port.out.NetworkNodeRepository;
import com.socialcoisa.rede.network.domain.NetworkNode;
import com.socialcoisa.rede.network.domain.NetworkRegions;
import com.socialcoisa.rede.network.domain.NodeHealth;

/**
 * A topologia da rede como <b>dado</b> para a tela desenhar (brief §8.5).
 *
 * <p>O painel lista nós um a um e agrega por região num mapa de contagem. Uma tela de grafo não
 * consegue desenhar isso sem reconstruir a topologia somando números soltos — e o servidor é quem
 * sabe quais nós compartilham um domínio de falha.</p>
 *
 * <p><b>O que a rede pode afirmar.</b> O único domínio maior que o nó presente no cadastro é a
 * <b>região</b>, a mesma que a colocação do Storage e a evacuação já usam. Host físico, rack,
 * operadora e link não estão registrados em lugar nenhum; derivá-los do nome, do IP ou do dono
 * seria deduzir topologia a partir de texto livre, e um domínio errado faz a rede acreditar numa
 * redundância que não existe.</p>
 *
 * <p><b>O que ela NÃO pode afirmar.</b> A tarefa não guarda o nó de origem — só o designado. Não há,
 * portanto, aresta "A entregou a B": as conexões entregues são as <b>declaradas pelo cadastro</b>
 * (nó pertence a domínio, nó tem perfis). Inventar o fluxo entre dois nós seria pior que não
 * mostrá-lo.</p>
 */
@Service
public class NetworkTopologyService {

	/** Mesmo teto da listagem do painel: desenhar nós que a tela não lista seria desenhar outra rede. */
	private static final int MAX_NODES = 200;

	/** Mesma janela de "vivo" do scheduler e do painel. */
	static final long ALIVE_SECONDS = 180;

	private final NetworkNodeRepository nodes;
	private final NetworkRegionService regions;
	private final Clock clock;
	private final Duration aliveAfter;

	NetworkTopologyService(NetworkNodeRepository nodes, NetworkRegionService regions, Clock clock,
			@Value("${rede.network.node-stale-seconds:180}") long staleSeconds) {
		this.nodes = nodes;
		this.regions = regions;
		this.clock = clock;
		// A janela vem da configuração, como no scheduler: dois cortes fariam o nó estar vivo no
		// grafo e morto na lista, no mesmo quadro.
		this.aliveAfter = Duration.ofSeconds(Math.max(1, staleSeconds));
	}

	@Transactional(readOnly = true)
	public Topology topology() {
		Instant now = clock.instant();
		List<NetworkNode> all = nodes.list(MAX_NODES);
		List<String> evacuated = regions.outOfService();

		Map<String, List<NetworkNode>> byDomain = new TreeMap<>();
		List<NodeRef> refs = new ArrayList<>(all.size());
		for (NetworkNode node : all) {
			String domain = NetworkRegions.normalize(node.region());
			byDomain.computeIfAbsent(domain, key -> new ArrayList<>()).add(node);
			boolean alive = node.alive(now, aliveAfter);
			refs.add(new NodeRef(node.id(), node.displayName(), node.status().name(), alive,
				node.quarantined(now), node.profiles().stream().map(Enum::name).sorted().toList(),
				node.region(), domain, NodeHealth.of(node, 0, now)));
		}

		List<DomainRef> domains = new ArrayList<>(byDomain.size());
		byDomain.forEach((domain, members) -> {
			long alive = members.stream().filter(node -> node.alive(now, aliveAfter)).count();
			domains.add(new DomainRef(domain, NetworkRegions.outOfService(evacuated, domain), members.size(), alive));
		});
		// Domínio com mais membros primeiro: é por onde a tela começa a desenhar. Empate pela chave,
		// para a ordem não mudar entre dois quadros sem que nada tenha mudado.
		domains.sort((a, b) -> a.nodes() != b.nodes() ? Long.compare(b.nodes(), a.nodes())
			: a.region().compareTo(b.region()));

		// Aresta declarada é sempre nó → domínio. O número é o mesmo de nós que declaram região: quem
		// não declarou não tem aresta para lugar nenhum, e um domínio próprio seria justamente
		// afirmar um vínculo que o cadastro não registra.
		long declared = all.stream().filter(node -> !NetworkRegions.normalize(node.region()).isEmpty()).count();
		return new Topology(now, refs, domains, declared, MAX_NODES, all.size() >= MAX_NODES);
	}

	/**
	 * @param generatedAt quando o quadro foi medido — "vivo" é sempre relativo a este instante
	 * @param nodes       um por nó listado, já com o domínio calculado
	 * @param domains     agrupamento por domínio de falha, mais membros primeiro
	 * @param declaredConnections nós com domínio declarado: as arestas nó → domínio que existem
	 * @param limit       teto da listagem aplicado
	 * @param truncated   a lista bateu no teto; há nós na rede que o quadro não mostra
	 */
	public record Topology(Instant generatedAt, List<NodeRef> nodes, List<DomainRef> domains,
			long declaredConnections, int limit, boolean truncated) {
	}

	/**
	 * @param alive       heartbeat dentro da janela — medido agora, distinto do {@code status}
	 * @param quarantined fora do balanceamento por janela vigente
	 * @param domain      região normalizada; vazio = não declarada (domínio próprio, distinto de todos)
	 * @param health      as quatro dimensões do §8.8, para a tela colorir sem uma segunda chamada
	 */
	public record NodeRef(String id, String name, String status, boolean alive, boolean quarantined,
			List<String> profiles, String region, String domain, NodeHealth health) {
	}

	/**
	 * @param region      região normalizada; vazio = nós sem região declarada
	 * @param outOfService regiões evacuadas não atendem ninguém, mesmo com membros vivos
	 */
	public record DomainRef(String region, boolean outOfService, long nodes, long alive) {
	}
}