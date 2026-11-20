package com.socialcoisa.rede.network.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.socialcoisa.rede.shared.web.ApiException;

/**
 * O <b>mesmo</b> dump para todos os pedidos de cópia dentro de uma janela curta (#953 item 4).
 *
 * <p>Onde isto vive, e por quê: a confirmação da cópia compara o digest que o nó gravou com o digest
 * do dump que o servidor <b>mandou</b>, e a comparação só faz sentido se "o dump mandado" for o mesmo
 * arquivo para todos. Quando cada {@code GET /network/standby/dump} gerava o banco de novo, dois nós
 * copiando ao mesmo tempo recebiam dumps diferentes, e o que confirmasse por último era recusado por
 * um digest que nunca teve culpa — e a retomada de uma conexão que caiu juntava a cauda de um dump
 * com o começo de outro. Em produção isso não é hipótese: a leitura leva minutos, e o refresh, o
 * painel e o nó podem pedir durante ela.</p>
 *
 * <p>Antes era um mapa estático no {@link StandbySyncService} — dois serviços vivos no mesmo contexto
 * dividiam a confirmação de um nó com o outro. Agora o digest mora no arquivo entregue, e a janela é
 * uma propriedade do contexto ({@code rede.network.standby.dump.cache-seconds}, 10 min).</p>
 *
 * <p>O custo da janela é conhecido e curto: uma escrita feita entre dois pedidos dentro dela não
 * aparece no dump posterior. O que ela evita é o banco de produção lido inteiro por cada tentativa de
 * retomada de cada nó.</p>
 */
@Component
public class StandbyDumpSnapshotProvider {

	private final StandbyDumpSource source;
	private final Clock clock;
	private final Duration window;
	private final Object state = new Object();

	/** Quem chega enquanto uma leitura roda não pede a mesma leitura: espera esta (a janela curta). */
	private final java.util.concurrent.locks.ReentrantLock generation = new java.util.concurrent.locks.ReentrantLock();

	/** O dump gerado e quando — sob {@link #state}. */
	private Snapshot windowDump;

	/** A tentativa que está com o dump na mão: ela recebe esse arquivo até a linha deixar de ser a dela. */
	private Snapshot claimed;
	private long claimedBy;

	public StandbyDumpSnapshotProvider(StandbyDumpSource source, Clock clock,
			@Value("${rede.network.standby.dump.cache-seconds:600}") long cacheSeconds) {
		this.source = source;
		this.clock = clock;
		this.window = Duration.ofSeconds(Math.max(0, cacheSeconds));
	}

	/**
	 * O dump que vale agora: o da janela se ela ainda vale, ou um recém-gerado e conferido.
	 *
	 * @throws IllegalStateException quando o banco não pode ser lido ou o dump não carrega — o
	 * serviço transforma isso em falha da cópia com motivo, nunca numa cópia que o nó aceita
	 */
	public Snapshot current() {
		Snapshot janela = inWindow();
		if (janela != null) {
			return janela;
		}
		generation.lock();
		try {
			// Quem esperou o lock pode achar aqui o dump que a leitura anterior acabou de gerar.
			janela = inWindow();
			return janela != null ? janela : generate();
		}
		finally {
			generation.unlock();
		}
	}

	/**
	 * O dump de uma tentativa <b>já começada</b>: a retomada tem de sair do mesmo arquivo que o começo.
	 *
	 * <p>Uma tentativa nova começa do zero, mesmo com um dump da janela na mão: a cópia dela é nova, e
	 * o arquivo que ela recebe precisa ser o que a confirmação dela vai cobrar. Quem diz qual é a
	 * tentativa é a linha da cópia, não a chegada do pedido.</p>
	 */
	public Snapshot forAttempt(long attemptStartedMillis) {
		generation.lock();
		try {
			synchronized (state) {
				if (claimed == null || claimedBy != attemptStartedMillis) {
					reserve(attemptStartedMillis, inWindow() != null ? windowDump : generate());
				}
				return claimed;
			}
		}
		finally {
			generation.unlock();
		}
	}

	/** Chamar com {@link #state} travado e, se for gerar, com {@link #generation} (é o mesmo caminho). */
	private void reserve(long attemptStartedMillis, Snapshot dump) {
		if (claimed != null && attemptStartedMillis < claimedBy) {
			// Retomada de uma tentativa ANTERIOR à que está com o arquivo: o que ela tem no disco veio de
			// outro arquivo, e esta instância não o tem mais. Recusar (416) é honesto; entregar a cauda de
			// outro dump recusaria a confirmação dela por um motivo falso.
			throw new ApiException(416, "STANDBY_OFFSET_BEYOND_DUMP",
				"A cópia que este pedido retoma já foi substituída por outra tentativa.");
		}
		claimed = dump;
		claimedBy = attemptStartedMillis;
	}

	/**
	 * A tentativa começou aqui e a linha dela está sendo gravada na transação corrente: quando ela
	 * commitar, o dump é dela. Numa transação sem commit (teste, diagnóstico), vale na hora.
	 */
	public void begunAt(long attemptStartedMillis) {
		synchronized (state) {
			if (claimed != null && claimedBy == attemptStartedMillis) {
				return;
			}
			reserve(attemptStartedMillis, windowDump);
		}
	}

	/**
	 * A tentativa deixou de existir (confirmada, falhou, ou uma nova começou na linha).
	 *
	 * <p>Só aqui o dump sai da mão dela. Uma cópia que ainda mande um pedido pendente recebe o mesmo
	 * arquivo (se a janela ainda vale) ou {@code 416} — nunca a cauda de outro dump.</p>
	 */
	public void release(long attemptStartedMillis) {
		synchronized (state) {
			if (claimed != null && claimedBy == attemptStartedMillis) {
				claimed = null;
				claimedBy = 0L;
			}
		}
	}

	/** Descarta o dump da janela (teste e diagnóstico: a próxima leitura lê o banco de novo). */
	public void forget() {
		synchronized (state) {
			windowDump = null;
		}
	}

	private Snapshot inWindow() {
		Snapshot atual = windowDump;
		if (atual == null || window.isZero()) {
			return null;
		}
		return Duration.between(atual.generatedAt(), clock.instant()).compareTo(window) < 0 ? atual : null;
	}

	/**
	 * Lê o banco — com {@link #generation} travado. O dump grande é a primeira leitura da janela, e um
	 * pedido de retomada chega antes dela terminar: quem espera o lock recebe o arquivo pronto, nunca
	 * meia linha do banco.
	 */
	private Snapshot generate() {
		byte[] bytes = source.dump(0L);
		if (!source.verify()) {
			throw new IllegalStateException(source.lastFailure() == null
				? "o dump gerado nao carrega" : source.lastFailure());
		}
		Snapshot novo = new Snapshot(bytes, clock.instant(), StandbySyncService.sha256(bytes));
		synchronized (state) {
			windowDump = novo;
		}
		return novo;
	}

	/**
	 * Um dump inteiro, com o que a cópia precisa saber dele.
	 *
	 * @param bytes o dump do começo ao fim
	 * @param generatedAt quando ele foi gerado
	 * @param sha256 o digest do dump inteiro — o que a confirmação de quem o recebeu tem de bater
	 */
	public record Snapshot(byte[] bytes, Instant generatedAt, String sha256) {

		/** O trecho a partir de {@code offset}; retomada depois do fim é 416, não uma cauda vazia. */
		public byte[] slice(long offset) {
			if (offset <= 0) {
				return bytes;
			}
			if (offset >= bytes.length) {
				throw new ApiException(416, "STANDBY_OFFSET_BEYOND_DUMP", "A retomada começou depois do fim do dump.");
			}
			return java.util.Arrays.copyOfRange(bytes, (int) offset, bytes.length);
		}
	}
}
