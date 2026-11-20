package com.socialcoisa.rede.network.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.network.domain.NetworkNode;
import com.socialcoisa.rede.network.domain.StoragePlacement;
import com.socialcoisa.rede.shared.audit.AuditTrail;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * O inventário de corpos que <b>este</b> processo guarda no próprio disco, comparado com o que o
 * catálogo diz que ele deve guardar.
 *
 * <h2>Por que existe</h2>
 * <p>Até aqui o Control Plane sabia o que o nó <b>deveria</b> ter (a colocação) e nada sobre o que
 * ele de fato tem. Três consequências ruins, todas silenciosas:</p>
 * <ul>
 * <li><b>poda impossível:</b> colocação removida no catálogo deixava o arquivo no disco para
 * sempre — o disco de quem contribui só cresce;</li>
 * <li><b>varredura cega para o nó local:</b> {@code StorageScrubber} só relê a réplica 0, porque
 * não sabia qual id de nó roda nesta máquina. Agora o processo sabe;</li>
 * <li><b>divergência invisível:</b> arquivo trocado no diretório do nó não aparecia em relatório
 * nenhum.</li>
 * </ul>
 *
 * <h2>Quem é "este nó"</h2>
 * <p>{@code rede.storage.node-id}. O mesmo processo que roda o Control Plane também pode ser um nó
 * ({@code NODE_LOCAL} guarda objeto). O id é configuração, não dedução: adivinhar por perfil daria
 * a um nó o inventário de outro. Ausente = este processo não guarda corpo de nó, e as rotas recusam
 * em vez de devolver lista vazia (lista vazia diria "não tenho nada", que é uma afirmação).
 *
 * <h2>Órfão ≠ divergência</h2>
 * <p>Órfão é arquivo sem colocação para este nó: pode ser lixo de tarefa interrompida ou objeto que
 * o operador descolocou. Divergente é arquivo que a colocação <b>promete</b> e cujo sha-256 não
 * confere. São ações diferentes (apagar vs. re-replicar), e o relatório não as junta.
 */
@Service
public class NodeStorageInventory {

	private static final Logger LOG = LoggerFactory.getLogger(NodeStorageInventory.class);

	/**
	 * O retrato: o que o catálogo promete a este nó, o que o disco tem, e as diferenças.
	 *
	 * <p>{@code orphanBytes} é zero quando não há órfão, e é um número medido — não uma estimativa.
	 * {@code missing} é colocado e não está no disco; {@code divergent} está no disco e não confere
	 * com o digest do catálogo.</p>
	 */
	public record Report(String nodeId, boolean installed, List<Entry> held, List<Missing> missing,
			List<Divergent> divergent, List<Orphan> orphan, long orphanBytes, long heldBytes) {
	}

	/** Um corpo que está no disco e confere com a colocação. */
	public record Entry(String objectId, int replica, long bytes, String sha256) {
	}

	/** O catálogo diz que este nó tem cópia e o disco não tem o arquivo. */
	public record Missing(String objectId, int replica, long bytes, String sha256, String state) {
	}

	/** O arquivo existe e o digest não é o que a colocação promete. {@code actualSha256} vazio = não legível. */
	public record Divergent(String objectId, int replica, String expectedSha256, String actualSha256) {
	}

	/** Arquivo no disco sem colocação para este nó. */
	public record Orphan(String objectId, long bytes) {
	}

	/** O que o operador pediu para apagar; {@code removed} é o que de fato saiu. */
	public record Purge(List<String> removed, List<String> kept, long freedBytes) {
	}

	private final StorageCatalogService storage;
	private final NodeBodyStore bodies;
	private final AuditTrail audit;

	NodeStorageInventory(StorageCatalogService storage, @Nullable NodeBodyStore bodies, AuditTrail audit) {
		this.storage = storage;
		this.bodies = bodies;
		this.audit = audit;
	}

	/** Este processo é um nó de armazenamento com disco próprio? */
	public boolean installed() {
		return bodies != null && bodies.ownerNodeId() != null;
	}

	/**
	 * Compara o disco com a colocação.
	 *
	 * @throws ApiException 409 quando este processo não guarda corpo de nó: devolver um relatório
	 *         vazio diria "nada falta, nada sobra", e não é isso que se sabe.
	 */
	@Transactional(readOnly = true)
	public Report inventory(NetworkNode caller) {
		if (!installed()) {
			throw new ApiException(409, "STORAGE_NODE_BODY_DISABLED",
				"Este processo nao guarda corpo de no (rede.storage.node-body-root/node-id ausentes).");
		}
		// O inventário é do próprio disco: só o nó cujo id é este pode pedi-lo. Sem esta guarda, um
		// nó qualquer perguntaria pelo inventário de outra máquina e receberia o desta.
		String nodeId = bodies.ownerNodeId();
		if (caller == null || !caller.id().equals(nodeId)) {
			throw new ApiException(403, "STORAGE_INVENTORY_NOT_OWN",
				"O inventario e do disco deste no; outro no nao pode consulta-lo.");
		}
		List<StoragePlacement> colocados = storage.placementsOn(nodeId).stream()
			.filter(p -> p.state() == StoragePlacement.State.OK || p.state() == StoragePlacement.State.PENDING_REMOTE)
			.toList();
		List<Entry> held = new java.util.ArrayList<>();
		List<Missing> missing = new java.util.ArrayList<>();
		List<Divergent> divergent = new java.util.ArrayList<>();
		long heldBytes = 0;
		for (StoragePlacement p : colocados) {
			if (!bodies.has(p.objectId())) {
				// PENDING_REMOTE ausente é o normal: a tarefa ainda não rodou. OK ausente é perda, e
				// os dois aparecem separados pelo campo `state` -- quem lê decide o que fazer.
				missing.add(new Missing(p.objectId(), p.replica(), p.bytes(), p.sha256(), p.state().name()));
				continue;
			}
			long tamanho = sizeOf(bodies.pathOf(p.objectId()));
			heldBytes += tamanho;
			String real = sha256(bodies.pathOf(p.objectId()));
			if (real.isEmpty() || p.sha256() == null || !real.equalsIgnoreCase(p.sha256())) {
				divergent.add(new Divergent(p.objectId(), p.replica(), p.sha256(), real));
				continue;
			}
			held.add(new Entry(p.objectId(), p.replica(), tamanho, real));
		}
		// `GONE` não é mais atribuído a este nó: o catálogo já recolheu a cópia (lápide coletada,
		// §4, ou cura que repôs a réplica em outro nó, §5.1.1). O arquivo, porém, continua no disco,
		// e nada mais o apagava — o protocolo só tem OBJECT_PUT/OBJECT_GET. O espaço voltava na
		// conta de folga cedida e não na máquina de quem contribui. Fora da lista de "conhecidos",
		// ele cai na poda junto com o órfão de verdade.
		//
		// `STALE` fica de fora de propósito: pode ser a única cópia possivelmente boa, e apagá-la
		// antes de o Control Plane repor transformaria divergência em perda.
		// Quem o nó NÃO pode apagar: o que está atribuído (`colocados`, acima) e a cópia `STALE` —
		// esta pode ser a única cópia possivelmente boa, e apagá-la antes de o Control Plane repor
		// transformaria divergência em perda. `GONE` é o contrário: o catálogo já recolheu a cópia
		// (lápide coletada no §4, cura que repôs a réplica em outro nó no §5.1.1) e o arquivo
		// continua no disco sem ninguém mandar apagar — o protocolo só tem OBJECT_PUT/OBJECT_GET. O
		// espaço voltava na conta de folga cedida e não na máquina de quem contribui.
		java.util.Set<String> intocaveis = storage.placementsOn(nodeId).stream()
			.filter(p -> p.state() != StoragePlacement.State.GONE)
			.map(StoragePlacement::objectId)
			.collect(java.util.stream.Collectors.toSet());
		List<Orphan> orphan = new java.util.ArrayList<>();
		long orphanBytes = 0;
		for (String noDisco : bodies.objectIds()) {
			if (intocaveis.contains(noDisco)) {
				continue;
			}
			long tamanho = sizeOf(bodies.pathOf(noDisco));
			orphan.add(new Orphan(noDisco, tamanho));
			orphanBytes += tamanho;
		}
		return new Report(nodeId, true, held, missing, divergent, orphan, orphanBytes, heldBytes);
	}

	/**
	 * Apaga corpos que o catálogo <b>não atribui mais</b> a este nó.
	 *
	 * <p>Não apaga {@code missing} nem {@code divergent}: os primeiros não existem, e os segundos
	 * são a única cópia possivelmente boa de um objeto — apagar antes de o Control Plane re-replicar
	 * transformaria divergência em perda. Só sai o que é <b>órfão</b>, arquivo que nenhuma colocação
	 * deste nó reivindica.</p>
	 *
	 * <p>{@code dryRun} é o padrão do chamador: apagar disco de quem contribui é ato de operador,
	 * com o número na mão.</p>
	 */
	public Purge purgeOrphans(NetworkNode caller, boolean dryRun) {
		Report relatorio = inventory(caller);
		List<String> removidos = new java.util.ArrayList<>();
		List<String> mantidos = new java.util.ArrayList<>();
		long liberado = 0;
		for (Orphan orfao : relatorio.orphan()) {
			if (dryRun) {
				mantidos.add(orfao.objectId());
				continue;
			}
			if (bodies.remove(orfao.objectId())) {
				removidos.add(orfao.objectId());
				liberado += orfao.bytes();
			}
			else {
				// Não saiu: dizer "removi" quando o arquivo continua lá faria o operador achar que
				// liberou espaço que ainda está ocupado.
				mantidos.add(orfao.objectId());
			}
		}
		if (!dryRun && !removidos.isEmpty()) {
			String dono = bodies.ownerNodeId();
			audit.record(dono, "STORAGE_ORPHANS_PURGED", dono,
				"{\"removed\":" + removidos.size() + ",\"freedBytes\":" + liberado + ",\"at\":\"" + Instant.now() + "\"}");
		}
		return new Purge(removidos, mantidos, liberado);
	}

	private static long sizeOf(Path path) {
		try {
			return Files.size(path);
		}
		catch (IOException unreadable) {
			return 0;
		}
	}

	/** Digest do corpo, ou vazio quando não foi possível ler (nunca um digest calculado sobre nada). */
	private static String sha256(Path path) {
		try (var in = Files.newInputStream(path)) {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] buffer = new byte[8192];
			int lidos;
			while ((lidos = in.read(buffer)) > 0) {
				digest.update(buffer, 0, lidos);
			}
			return HexFormat.of().formatHex(digest.digest());
		}
		catch (IOException | NoSuchAlgorithmException unreadable) {
			LOG.warn("Nao foi possivel medir {}: {}", path, unreadable.getMessage());
			return "";
		}
	}
}