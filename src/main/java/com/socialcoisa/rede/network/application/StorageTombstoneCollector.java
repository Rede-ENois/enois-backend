package com.socialcoisa.rede.network.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.network.application.port.out.StorageCatalogRepository;
import com.socialcoisa.rede.network.domain.StorageObject;
import com.socialcoisa.rede.network.domain.StorageObjectState;
import com.socialcoisa.rede.network.domain.StoragePlacement;
import com.socialcoisa.rede.shared.audit.AuditTrail;

/**
 * A coleta de uma lápide do Storage (brief §14): além de marcar {@code DELETED}, o objeto pode
 * voltar a ter cópias depois da remoção — um upload que venceu a corrida, ou uma cópia da fase 3 que
 * chegou atrasada. Enquanto essas linhas existirem, o objeto "removido" continua contando na
 * durabilidade e pode ser ressuscitado por um caminho que não conhece a lápide.
 *
 * <p><b>Ordem, não escolha.</b> A durabilidade de um objeto vivo é lida de {@code placements()} —
 * todas as cópias daquele objeto, em qualquer estado — e não de um filtro por estado: o healing
 * recebe {@code texto} mas complementa com a própria leitura do catálogo, e uma cópia {@code GONE}
 * que ainda tivesse bytes no disco deixaria de aparecer. Fechar a lacuna com um filtro
 * {@code <> 'GONE'} seria consertar cada leitor e deixar o próximo nascer errado.</p>
 *
 * <p><b>Lápide com cópia é rastro, não lixo.</b> Varre-se primeiro, marca-se a lápide depois. Se um
 * nó confirmar a cópia entre a leitura e a marcação, o objeto foi servível naquele instante e a
 * próxima varredura recolhe a linha; o contrário (apagar a cópia e servir objeto inexistente)
 * perderia o único caminho de leitura no instante em que ele ainda valia.</p>
 */
@Service
public class StorageTombstoneCollector {

	/** Teto de objetos varridos por chamada: uma transação não varre o catálogo inteiro. */
	private static final int MAX_OBJECTS = 200;

	/** Teto de páginas: catálogo grande é podado ao longo de chamadas, não numa só. */
	private static final int MAX_PAGES = 20;

	private final StorageCatalogRepository catalog;
	private final AuditTrail audit;
	private final Clock clock;
	private final Duration retention;

	StorageTombstoneCollector(StorageCatalogRepository catalog, AuditTrail audit, Clock clock,
			@Value("${rede.storage.tombstone.retention-hours:72}") long retentionHours) {
		this.catalog = catalog;
		this.audit = audit;
		this.clock = clock;
		this.retention = Duration.ofHours(Math.max(1, retentionHours));
	}

	/**
	 * Recolhe as lápides vencidas. {@code dryRun} mostra o que sairia sem apagar nada — é o padrão
	 * das operações de coleta do módulo, e a primeira coisa que um operador faz num incidente.
	 */
	@Transactional
	public Report collect(boolean dryRun) {
		Instant now = clock.instant();
		Instant limite = now.minus(retention);
		List<Collected> coletados = new ArrayList<>();
		int varridos = 0;
		Cursor after = null;
		for (int pagina = 0; pagina < MAX_PAGES && varridos < MAX_OBJECTS; pagina++) {
			List<StorageObject> lapides = catalog.listDeletedBefore(limite, after == null ? null : after.bucket(), after == null ? null : after.key(),
				Math.min(200, MAX_OBJECTS - varridos));
			if (lapides.isEmpty()) {
				break;
			}
			for (StorageObject objeto : lapides) {
				if (varridos >= MAX_OBJECTS) {
					break;
				}
				varridos++;
				// Vivo de novo é o OBJETO, não a cópia: uma lápide sempre tem a réplica 0 OK (é ela
				// que guardou os bytes até a remoção), e pular por "tem cópia OK" deixaria toda lápide
				// para trás. Revista na leitura é a única forma honesta de ver a corrida: entre a
				// página e esta linha, um upload pode ter marcado o objeto de volta.
				StorageObject agora = catalog.findById(objeto.id()).orElse(null);
				if (agora == null || agora.state() != StorageObjectState.DELETED) {
					continue;
				}
				List<StoragePlacement> copias = catalog.copies(objeto.id());
				Set<String> resgatados = new HashSet<>();
				for (StoragePlacement cpia : copias) {
					if (cpia.state() == StoragePlacement.State.GONE) {
						// Já coletada por quem confirmou que o disco não tem mais nada: reescrever
						// regravaria a data e apagaria o rastro de quando a coleta aconteceu.
						resgatados.add(cpia.nodeId());
						continue;
					}
					if (!dryRun) {
						catalog.savePlacement(new StoragePlacement(objeto.id(), cpia.replica(), cpia.nodeId(),
							cpia.bytes(), cpia.sha256(), StoragePlacement.State.GONE, now));
					}
					resgatados.add(cpia.nodeId());
				}
				coletados.add(new Collected(objeto.id(), objeto.bucket(), objeto.key(),
					objeto.deletedAt(), resgatados.size()));
			}
			// O cursor avança SEMPRE, e mesmo quando o objeto foi pulado: é o que separa "esta
			// página acabou" de "esta página não rendeu". Avançar só para quem foi coletado relia a
			// mesma página até o teto de páginas -- medido: uma lápide viva consumia as 20 páginas.
			StorageObject ultimo = lapides.get(lapides.size() - 1);
			after = new Cursor(ultimo.bucket(), ultimo.key());
			if (varridos >= MAX_OBJECTS) {
				break;
			}
		}
		if (!dryRun && !coletados.isEmpty()) {
			audit.record("system", "STORAGE_TOMBSTONES_COLLECTED", "storage",
				"{\"objects\":" + coletados.size() + ",\"retentionHours\":" + retention.toHours() + "}");
		}
		return new Report(dryRun, now, retention.toHours(), varridos, List.copyOf(coletados),
			MAX_OBJECTS, varridos >= MAX_OBJECTS);
	}

	/**
	 * Posição na ordenação total {@code (bucket, chave)}. Só a chave não basta: a mesma chave em
	 * dois buckets é a chave do cursor da varredura de durabilidade, e aqui também daria volta
	 * infinita entre as duas linhas.
	 */
	private record Cursor(String bucket, String key) {
	}

	/**
	 * @param retentionHours janela entre a lápide e a coleta — tempo para um operador desfazer
	 * @param scanned        objetos-lápide visitados (inclui os pulados por estarem vivos)
	 * @param collected      um por lápide cujas cópias foram recolhidas (ou seriam, em {@code dryRun})
	 * @param truncated      a varredura parou no teto; pode haver mais lápide vencida
	 */
	public record Report(boolean dryRun, Instant generatedAt, long retentionHours, int scanned,
			List<Collected> collected, int maxObjects, boolean truncated) {
	}

	/**
	 * @param copiesCollected cópias viradas {@code GONE} — inclui as que já estavam, para o número
	 *                        dizer quantos nós deixaram de prometer o objeto
	 */
	public record Collected(String objectId, String bucket, String key, Instant deletedAt, int copiesCollected) {
	}
}