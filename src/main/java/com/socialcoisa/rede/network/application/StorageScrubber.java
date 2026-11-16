package com.socialcoisa.rede.network.application;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.network.application.port.out.StorageCatalogRepository;
import com.socialcoisa.rede.network.domain.StorageObject;
import com.socialcoisa.rede.network.domain.StoragePlacement;
import com.socialcoisa.rede.shared.audit.AuditTrail;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Varredura de durabilidade: encontra os objetos que o catálogo diz {@code STORED} mas que não têm
 * as cópias que a configuração pede.
 *
 * <p>É a parte de "scrubbing" da fase 3 que dá para fazer com o que existe. Ela <b>não</b> lê byte
 * por byte comparando hash — isso exige que a réplica sirva o corpo para o Control Plane, e é o
 * próximo passo (o contrato de leitura por digest já existe, {@code GET /objects/by-digest}). O que
 * ela faz é o que a colocação permite afirmar:</p>
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

	StorageScrubber(StorageCatalogRepository catalog, StoragePlacementService placement, AuditTrail audit,
			Clock clock, StorageCatalogService bodies) {
		this.catalog = catalog;
		this.placement = placement;
		this.audit = audit;
		this.clock = clock;
		this.bodies = bodies;
	}

	/** O que a varredura encontrou, incluindo os objetos que precisam de cópia nova. */
	public record Report(int replicasExpected, int objectsScanned, List<UnderReplicated> underReplicated,
			int bodiesRelidos, List<Divergent> divergent) {

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
	 * <p>{@code readBodies} releia o corpo de cada cópia {@code OK} que este Control Plane hospeda e
	 * compara com o sha-256 do catálogo. Sem ele, a varredura só conta linhas: um arquivo corrompido
	 * no disco, truncado por um <i>crash</i> ou trocado por outro processo continua contando como
	 * cópia confirmada, e o catálogo promete durabilidade que não tem.</p>
	 *
	 * <p>Cópia de outro nó <b>não</b> é relida aqui: o Control Plane não inicia conexão para o nó
	 * (o nó atrás de NAT só faz saída, e o brief proíbe cadastro de endereço). Reler o que está em
	 * outro lugar é trabalho da próxima rodada do nó, não deste lado.</p>
	 */
	@Transactional(readOnly = true)
	public Report scan(String bucket, int maxObjects, boolean readBodies) {
		int expected = placement.targetReplicas();
		List<Report.UnderReplicated> gaps = new ArrayList<>();
		List<Report.Divergent> divergentes = new ArrayList<>();
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
				List<StoragePlacement> confirmed = catalog.placements(id)
					.stream()
					.filter(p -> p.state() == StoragePlacement.State.OK)
					.toList();
				if (confirmed.size() < expected) {
					gaps.add(new Report.UnderReplicated(id, confirmed.size(), expected - confirmed.size(),
						confirmed.stream().map(StoragePlacement::nodeId).toList()));
				}
				if (readBodies) {
					for (StoragePlacement copia : confirmed) {
						// Só o que está NESTE Control Plane pode ser relido: o corpo dos outros nós não fica aqui.
						// Não há id fixo de CP — qualquer nó pode gravar pelo PUT e o corpo fica no
						// diretório desta instalação. Arquivo ausente NÃO é pulado: é justamente a
						// divergência que a releitura existe para encontrar (o catálogo promete uma
						// cópia que o disco não tem), e quem a detecta é o readBody logo abaixo.
						if (copia.sha256() == null) {
							continue;
						}
						relidos++;
						try (InputStream body = bodies.readBody(id)) {
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
		return new Report(expected, scanned, List.copyOf(gaps), relidos, List.copyOf(divergentes));
	}

}