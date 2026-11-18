package com.socialcoisa.rede.network.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.socialcoisa.rede.network.application.port.out.StorageCatalogRepository;
import com.socialcoisa.rede.network.domain.StorageObject;
import com.socialcoisa.rede.network.domain.StoragePlacement;

/**
 * O relógio da fase 3: designa a lacuna de durabilidade sem ninguém apertar o botão.
 *
 * <h2>O buraco que isto fecha</h2>
 * <p>Até aqui {@link StorageHealer} era um ato de operador ({@code POST /admin/network/nodes/storage/heal}).
 * Sem ninguém chamando, um nó que saísse da rede deixava a cópia dele {@code GONE} e a lacuna ficava
 * aberta indefinidamente — o scrub dedurava, o painel mostrava, e nada acontecia. Um objeto guardado
 * por uma única cópia continua {@code SINGLE_DOMAIN} até alguém olhar.</p>
 *
 * <h2>O que ele NÃO faz</h2>
 * <p>Não apaga nada, não grava corpo e não confirma cópia: só <b>designa</b>, pelo mesmo caminho do
 * healer (a colocação de {@link StoragePlacementService}) e pelo mesmo despacho
 * ({@link StorageReplicationDispatcher}). A confirmação continua sendo do nó, com bytes e sha-256
 * conferidos. O custo de disco e banda é de quem contribui, e é por isso que o lote é pequeno e o
 * intervalo é longo.</p>
 *
 * <h2>O problema que o operador não tinha como ver</h2>
 * <p>Uma designação que o nó nunca confirma — nó sumiu depois do despacho, task perdida, disco cheio —
 * ficava {@code PENDING_REMOTE} para sempre, e duas consequências ruins vinham juntas:</p>
 * <ul>
 *   <li>{@code StorageHealer.temDesignacaoPendente} tratava a pendência eterna como "a lacuna tem
 *   dono", e o operador não conseguia curar aquilo nem manualmente;</li>
 *   <li>a cópia "a caminho" nunca chegava, e a durabilidade continuava baixa sem ninguém saber por quê.</li>
 * </ul>
 * <p>Passado o prazo ({@code rede.storage.replication.pending-max-age-minutes}), a designação vira
 * {@code GONE} — não apagada, para o histórico mostrar que houve uma tentativa — e a lacuna volta a
 * ser curável. {@code GONE} não é servível e não conta para a durabilidade, que é exatamente a
 * verdade: aquele nó não tem a cópia.</p>
 *
 * <h2>Desligado por padrão</h2>
 * <p>{@code rede.storage.replication.auto-heal-enabled=false} de fábrica. Ligar é decisão de quem
 * opera a rede: o worker designa sem pedido humano, e o tráfego de corpo tem custo para o
 * participante. Desligado, o comportamento é o de antes — o healer só cura quando chamado.</p>
 */
@Service
public class StorageReplicationWorker {

	static final Logger LOG = LoggerFactory.getLogger(StorageReplicationWorker.class);

	/**
	 * Objetos por passada. Baixo de propósito: cada objeto designado vira tarefa e tráfego de corpo.
	 * Um catálogo grande é curado em várias passadas, e o operador decide o ritmo pelo intervalo.
	 */
	static final int MAX_OBJECTS_PER_RUN = 25;

	/** Atores da automação na trilha de auditoria: não há sessão, mas há quem responder. */
	static final String ATOR = "storage-replication-worker";

	private final StorageHealer healer;
	private final StorageReplicationDispatcher dispatcher;
	private final StorageCatalogRepository catalog;
	private final Clock clock;
	private final boolean enabled;
	private final Duration pendingMaxAge;

	StorageReplicationWorker(StorageHealer healer, StorageReplicationDispatcher dispatcher,
			StorageCatalogRepository catalog, Clock clock,
			@Value("${rede.storage.replication.auto-heal-enabled:false}") boolean enabled,
			@Value("${rede.storage.replication.pending-max-age-minutes:60}") int pendingMaxAgeMinutes) {
		this.healer = healer;
		this.dispatcher = dispatcher;
		this.catalog = catalog;
		this.clock = clock;
		this.enabled = enabled;
		// Zero ou negativo seria uma designação vencendo no instante em que nasce: o piso é um minuto.
		this.pendingMaxAge = Duration.ofMinutes(Math.max(1, pendingMaxAgeMinutes));
	}

	@Scheduled(initialDelayString = "${rede.storage.replication.initial-delay-ms:300000}",
		fixedDelayString = "${rede.storage.replication.delay-ms:900000}")
	public void run() {
		if (!enabled) {
			return;
		}
		try {
			tick(clock.instant());
		}
		catch (RuntimeException falha) {
			// O agendador derruba o worker inteiro se a exceção escapa. Uma passada ruim não pode
			// desligar a durabilidade até o próximo restart.
			LOG.warn("storage replication: passada falhou: {}", falha.getMessage());
		}
	}

	/**
	 * Uma passada: solta as designações vencidas e depois cura. Devolve o que fez, para teste e log.
	 */
	public Result tick(Instant now) {
		int soltas = soltarDesignacoesVencidas(now);
		var relatorio = healer.heal(ATOR, null, MAX_OBJECTS_PER_RUN, false);

		// Designar sem despachar deixava a cópia esperando um nó que a puxasse sozinho — o mesmo
		// buraco do despacho manual. Aqui cada objeto designado vira OBJECT_PUT na fila do nó.
		int despachadas = 0;
		int puladas = 0;
		for (String objectId : relatorio.designated().stream().map(StorageHealer.HealedObject::objectId)
			.distinct().toList()) {
			var despacho = dispatcher.dispatch(objectId);
			despachadas += despacho.dispatched().size();
			puladas += despacho.skipped().size();
		}
		if (soltas > 0 || despachadas > 0) {
			LOG.info("storage replication: designacoes vencidas={} despachadas={} puladas={}", soltas, despachadas,
				puladas);
		}
		return new Result(soltas, despachadas, puladas, relatorio.designated().size(), relatorio.skipped().size());
	}

	/**
	 * {@code PENDING_REMOTE} velho demais vira {@code GONE}.
	 *
	 * <p>A idade é da <b>designação</b>, não da cópia: o catálogo não guarda quando a linha nasceu, e
	 * {@code verified_at} de uma pendente é o instante em que foi designada ({@code savePlacement} a
	 * grava com {@code now}). Uma pendência que já ficou mais que o prazo sem confirmação não vai
	 * confirmar sozinha — o despacho já aconteceu e o nó não respondeu, ou a tarefa se perdeu.</p>
	 *
	 * <p>Varre o catálogo em páginas: o worker não pode segurar todos os objetos na memória nem fazer
	 * uma consulta por objeto. Só linha {@code STORED} interessa — objeto pendente ou removido não tem
	 * durabilidade a medir.</p>
	 */
	private int soltarDesignacoesVencidas(Instant now) {
		Instant limite = now.minus(pendingMaxAge);
		int soltas = 0;
		String afterKey = null;
		while (true) {
			List<StorageObject> pagina = catalog.listAll(afterKey, StorageCatalogService.MAX_LIST_LIMIT);
			if (pagina.isEmpty()) {
				break;
			}
			for (StorageObject objeto : pagina) {
				afterKey = objeto.key();
				if (!objeto.servable()) {
					continue;
				}
				for (StoragePlacement copia : catalog.copies(objeto.id())) {
					if (copia.state() != StoragePlacement.State.PENDING_REMOTE) {
						continue;
					}
					Instant designada = copia.verifiedAt();
					if (designada != null && designada.isAfter(limite)) {
						continue;
					}
					catalog.savePlacement(new StoragePlacement(copia.objectId(), copia.replica(), copia.nodeId(),
						copia.bytes(), copia.sha256(), StoragePlacement.State.GONE, now));
					soltas++;
					// Sem a marcação, a passada seguinte tenta soltar o que já soltou — a linha desta
					// passada mudou de estado no banco, mas a lista em memória leria a linha de novo.
				}
			}
			if (pagina.size() < StorageCatalogService.MAX_LIST_LIMIT) {
				break;
			}
		}
		if (soltas > 0) {
			LOG.warn("storage replication: {} designacao(oes) sem confirmacao viraram GONE", soltas);
		}
		return soltas;
	}

	/** Se este Control Plane designa lacuna sem operador. O painel mostra isso ao lado da durabilidade. */
	public boolean enabled() {
		return enabled;
	}

	/** O que uma passada fez. {@code designated} e {@code skipped} vêm do relatório do healer. */
	public record Result(int released, int dispatched, int dispatchSkipped, int designated, int skipped) {
	}
}