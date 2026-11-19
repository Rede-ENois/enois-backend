package com.socialcoisa.rede.network.application;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.network.application.port.out.NetworkNodeRepository;
import com.socialcoisa.rede.network.application.port.out.StorageCatalogRepository;
import com.socialcoisa.rede.network.domain.NetworkNode;
import com.socialcoisa.rede.network.domain.NetworkRegions;
import com.socialcoisa.rede.network.domain.StorageObject;
import com.socialcoisa.rede.network.domain.StoragePlacement;
import com.socialcoisa.rede.shared.audit.AuditTrail;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Varredura de durabilidade: encontra os objetos que o catálogo diz {@code STORED} mas que não têm
 * as cópias que a configuração pede.
 *
 * <p>É a parte de "scrubbing" da fase 3 que dá para fazer com o que existe. Ela conta as cópias
 * {@code OK} e, opcionalmente, relê a <b>réplica 0</b> — o arquivo da raiz deste Control Plane —
 * comparando tamanho e sha-256 com o medido. Cópia de outro nó não é relida daqui: o Control Plane
 * não inicia conexão para o nó (NAT, e o brief proíbe cadastrar endereço). O que ela afirma:</p>
 *
 * <ul>
 * <li>conta só cópias {@code OK}: designada e não confirmada não é cópia, e é justamente o caso que
 * mais engana um painel que conte linhas;</li>
 * <li>devolve a <b>lacuna</b> — quantas faltam e em quais objetos —, não um booleano de saúde. Um
 * "está tudo ok" genérico esconderia o objeto que só tem uma cópia;</li>
 * <li>não apaga nem corrige nada. Reparar exige escolher nó e reenviar o corpo, e isso é decisão
 * (e custo) de quem opera, não efeito colateral de uma varredura.</li>
 * </ul>
 */
@Service
public class StorageScrubber {

	private final StorageCatalogRepository catalog;
	private final StoragePlacementService placement;
	private final AuditTrail audit;
	private final Clock clock;
	private final StorageCatalogService bodies;
	/** Nulo quando este processo não guarda corpo de nó: aí só a réplica 0 é relida. */
	private final NodeBodyStore nodeBodies;
	/** Para ler a região de cada nó que tem cópia — a diversidade mora na região, não no nó. */
	private final NetworkNodeRepository nodes;

	StorageScrubber(StorageCatalogRepository catalog, StoragePlacementService placement, AuditTrail audit,
			Clock clock, StorageCatalogService bodies, @Nullable NodeBodyStore nodeBodies,
			NetworkNodeRepository nodes) {
		this.catalog = catalog;
		this.placement = placement;
		this.audit = audit;
		this.clock = clock;
		this.bodies = bodies;
		this.nodeBodies = nodeBodies;
		this.nodes = nodes;
	}

	/**
	 * Quanta redundância <b>externa</b> a rede oferece para um objeto (brief §9). A contagem de
	 * cópias não responde sozinha: três cópias no mesmo domínio de falha caem juntas.
	 */
	public enum Durability {
		/**
		 * As cópias do objeto estão todas no <b>mesmo domínio de falha</b> — inclusive o caso de uma
		 * só. Perder aquele nó (ou aquela região) é perder o objeto; o §9 chama de "sem redundância
		 * externa" quando existe um único storage node.
		 */
		SINGLE_DOMAIN,
		/** Cópias em domínios distintos, mas ainda menos que o pedido da configuração. */
		CROSS_REGION_PARTIAL,
		/** Cópias suficientes, em domínios distintos: é o que a política pede. */
		CROSS_REGION
	}

	/**
	 * Classifica as cópias <b>OK</b> de um objeto. Régua só com dado real:</p>
	 *
	 * <p>A réplica 0 é o Control Plane, e o CP é um domínio próprio: uma cópia num storage node
	 * sobrevive à queda do CP, e a do CP sobrevive à saída daquele nó. Nó sem região declarada é
	 * domínio <b>desconhecido</b> — não é igual à região de outro (regra de {@code NetworkRegions}),
	 * e por isso conta como domínio distinto em vez de se fundir com qualquer um.</p>
	 *
	 * <p>Nó que o cadastro não devolve conta como domínio desconhecido e distinto: o que a rede sabe
	 * é que ele tem cópia; afirmar que é a mesma região de outro seria inventar.</p>
	 */
	public Durability durability(String objectId, List<StoragePlacement> confirmed, int expected) {
		List<String> domains = domainsOf(confirmed);
		// Uma cópia só: não há redundância alguma, externa ou não. É o caso do §9 ("se existir
		// somente um storage node ... sem redundância externa") e também o objeto que perdeu todas
		// as cópias menos uma.
		if (domains.size() <= 1) {
			return Durability.SINGLE_DOMAIN;
		}
		return confirmed.size() < expected ? Durability.CROSS_REGION_PARTIAL : Durability.CROSS_REGION;
	}

	/**
	 * Domínios distintos ocupados pelas cópias, na forma comparável. A réplica 0 tem domínio
	 * próprio; cada nó contribui com a região normalizada, ou com uma chave única quando não há
	 * região (desconhecido não se funde com desconhecido).
	 */
	private List<String> domainsOf(List<StoragePlacement> confirmed) {
		List<String> domains = new ArrayList<>();
		for (StoragePlacement copia : confirmed) {
			String domain;
			if (copia.replica() == 0) {
				domain = "control-plane";
			}
			else {
				String region = nodes.findById(copia.nodeId()).map(NetworkNode::region).orElse(null);
				String normalized = NetworkRegions.normalize(region);
				domain = normalized.isEmpty() ? "unknown:" + copia.nodeId() : "region:" + normalized;
			}
			if (!domains.contains(domain)) {
				domains.add(domain);
			}
		}
		return domains;
	}

	/** O que a varredura encontrou, incluindo os objetos que precisam de cópia nova. */
	public record Report(int replicasExpected, int objectsScanned, List<UnderReplicated> underReplicated,
			int bodiesRelidos, List<Divergent> divergent, Map<String, Long> byDurability) {

		/** Objeto com menos cópias confirmadas que o pedido, e quantas faltam. */
		public record UnderReplicated(String objectId, int confirmed, int missing, List<String> nodesWithCopy) {
		}

		/**
		 * Uma cópia confirmada cujo corpo, relido, não tem o sha-256 que o catálogo guarda.
		 *
		 * <p>É informação diferente de "faltam cópias": aqui o catálogo <b>promete</b> uma cópia que
		 * existe e não confere. Um objeto com cópias faltando ainda pode ser servido pela que sobrou;
		 * este não pode ser servido pela cópia em questão.</p>
		 */
		public record Divergent(String objectId, int replica, String nodeId, String expectedSha256,
				String actualSha256) {
		}
	}

	/**
	 * Percorre os objetos de um bucket (ou de todos, com {@code bucket} nulo) em páginas.
	 *
	 * @param maxObjects teto de objetos por rodada: a varredura é periódica e não pode monopolizar o
	 * catálogo nem a consulta de cada objeto num bucket grande.
	 */
	@Transactional(readOnly = true)
	public Report scan(String bucket, int maxObjects) {
		return scan(bucket, maxObjects, false);
	}

	/**
	 * Varredura de durabilidade com o passo de releitura.
	 *
	 * <p>{@code readBodies} relê o corpo da <b>réplica 0</b> — a cópia que este Control Plane
	 * hospeda na própria raiz — e compara com o sha-256 do catálogo. Sem ele, a varredura só conta
	 * linhas: um arquivo corrompido no disco, truncado por um <i>crash</i> ou trocado por outro
	 * processo continua contando como cópia confirmada, e o catálogo promete durabilidade que não
	 * tem.</p>
	 *
	 * <p>Relê o disco <b>desta</b> instalação: a réplica 0 e, quando o processo também é um nó, as
	 * cópias do diretório dele. Cópia de outro nó <b>não</b> é relida: o Control Plane não inicia
	 * conexão para o nó (o nó atrás de NAT só faz saída, e o brief proíbe cadastro de endereço).</p>
	 */
	@Transactional(readOnly = true)
	public Report scan(String bucket, int maxObjects, boolean readBodies) {
		int expected = placement.targetReplicas();
		List<Report.UnderReplicated> gaps = new ArrayList<>();
		List<Report.Divergent> divergentes = new ArrayList<>();
		// Quantos objetos por nível, para o painel dizer a durabilidade EFETIVA (§9) sem afirmar a
		// que a política pede: dois objetos com 2 cópias podem estar em níveis diferentes.
		Map<String, Long> porNivel = new LinkedHashMap<>();
		int relidos = 0;
		int scanned = 0;
		String after = null;
		while (scanned < Math.max(1, maxObjects)) {
			List<StorageObject> page = bucket == null
				? catalog.listAll(after, Math.min(200, Math.max(1, maxObjects) - scanned))
				: catalog.list(bucket, "", after, Math.min(200, Math.max(1, maxObjects) - scanned));
			if (page.isEmpty()) {
				break;
			}
			for (StorageObject object : page) {
				scanned++;
				String id = object.id();
				after = object.key();
				// Só objeto servível tem durabilidade a medir: PENDING ainda não tem corpo, DELETED
				// foi removido de propósito.
				if (!object.servable()) {
					continue;
				}
				// Durabilidade lê `copies` (todos os estados), não `placements` (só OK): a pergunta é
				// quantas cópias o objeto tem, e uma cópia GONE ainda conta como réplica existente.
				List<StoragePlacement> confirmed = catalog.copies(id)
					.stream()
					.filter(p -> p.state() == StoragePlacement.State.OK)
					.toList();
				Durability nivel = durability(id, confirmed, expected);
				porNivel.merge(nivel.name(), 1L, Long::sum);
				if (confirmed.size() < expected) {
					gaps.add(new Report.UnderReplicated(id, confirmed.size(), expected - confirmed.size(),
						confirmed.stream().map(StoragePlacement::nodeId).toList()));
				}
				if (readBodies) {
					// Relê o que está no disco DESTA instalação, e só isso: a réplica 0 (a raiz do
					// Control Plane) e, quando este processo também é um nó, as cópias do disco dele.
					// Cópia de outro nó não é relida: o CP não inicia conexão para o nó (o brief
					// proíbe cadastrar endereço, e o nó atrás de NAT só faz saída).
					//
					// Antes o laço percorria TODAS as cópias OK e relia o mesmo arquivo local para
					// cada uma: com a raiz vazia, acusava de divergente a cópia de outro nó que está
					// íntegra, e `bodiesRelidos` contava N leituras de um arquivo só.
					// O dono do disco vem do próprio store: é ele que lê `node.json` e `api-key`.
					String donoDesteDisco = nodeBodies == null ? null : nodeBodies.ownerNodeId();
					for (StoragePlacement copia : confirmed) {
						if (copia.sha256() == null) {
							continue;
						}
						boolean replicaZero = copia.replica() == 0;
						boolean noLocal = donoDesteDisco != null && copia.nodeId().equals(donoDesteDisco);
						if (!replicaZero && !noLocal) {
							continue;
						}
						// Instalação sem corpo local (o catálogo tem uma réplica 0 que este processo
						// não hospeda — é o caso do Control Plane servido sem disco de corpo): não há
						// o que reler, e isso NÃO é divergência. Antes o `catch` abaixo transformava
						// a ausência do serviço de corpo em "a cópia do CP não confere".
						if (replicaZero && bodies == null) {
							continue;
						}
						if (!replicaZero && nodeBodies == null) {
							continue;
						}
						relidos++;
						try (InputStream body = replicaZero
								? bodies.openReplicaZeroBody(id)
								: bodies.openNodeBody(id)) {
							StorageCatalogService.Measured lido = StorageCatalogService.measureStream(body);
							if (!lido.sha256().equalsIgnoreCase(copia.sha256()) || lido.bytes() != copia.bytes()) {
								divergentes.add(new Report.Divergent(id, copia.replica(), copia.nodeId(),
									copia.sha256(), lido.sha256()));
							}
						}
						catch (ApiException sumiu) {
							// Corpo ausente ou ilegível é a mesma divergência vista por outro ângulo: o
							// catálogo promete uma cópia que o disco não tem. Digest vazio representa
							// "não consegui ler", nunca um digest calculado.
							divergentes.add(new Report.Divergent(id, copia.replica(), copia.nodeId(),
								copia.sha256(), ""));
						}
						catch (IOException closeFailed) {
							divergentes.add(new Report.Divergent(id, copia.replica(), copia.nodeId(),
								copia.sha256(), ""));
						}
					}
				}
			}
			if (page.size() < 200) {
				break;
			}
		}
		if (!gaps.isEmpty()) {
			Instant now = clock.instant();
			audit.record("system", "STORAGE_SCRUB_UNDER_REPLICATED", bucket == null ? "*" : bucket,
				"{\"objects\":" + gaps.size() + ",\"expected\":" + expected + ",\"at\":\"" + now + "\"}");
		}
		if (!divergentes.isEmpty()) {
			// Divergência é evento auditado como as demais ações de Storage: uma cópia que não
			// confere é o começo de uma perda de dado, não um detalhe de relatório.
			audit.record("system", "STORAGE_SCRUB_DIVERGENT", bucket == null ? "*" : bucket,
				"{\"copies\":" + divergentes.size() + ",\"at\":\"" + clock.instant() + "\"}");
		}
		return new Report(expected, scanned, List.copyOf(gaps), relidos, List.copyOf(divergentes), porNivel);
	}

}