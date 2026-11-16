package com.socialcoisa.rede.shared.web;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Contador em memória das recusas 401/403 em rota administrativa, por identidade.
 *
 * <p>Existe para responder "esta recusa é a primeira ou a quadragésima em dez minutos?" sem tocar no
 * banco. A linha de auditoria sai só no cruzamento do limiar; a contagem inteira fica aqui, por pod,
 * com teto de chaves — quem varre rotas com identidades sempre novas não cresce o mapa sem limite, e
 * as identidades mais antigas são descartadas quando o teto é atingido.</p>
 *
 * <p>Por que um contador e não uma linha por recusa: a trilha de auditoria tem chave primária e é a
 * tabela mais quente do sistema. Gravar cada 401/403 transformaria varredura em carga de escrita —
 * exatamente o efeito que quem varre procura.</p>
 */
@Component
public class AdminDenialCounter {

	private static final int MAX_IDENTITIES = 10_000;

	private final Clock clock;
	private final int threshold;
	private final long windowMs;
	private final Map<String, Stamps> windows = new ConcurrentHashMap<>();

	public AdminDenialCounter(Clock clock,
			@Value("${rede.security.denied-admin-alerts.threshold:20}") int threshold,
			@Value("${rede.security.denied-admin-alerts.window-minutes:10}") long windowMinutes) {
		this.clock = clock;
		this.threshold = Math.max(1, threshold);
		this.windowMs = Math.max(1, windowMinutes) * 60_000L;
	}

	/** Conta a recusa agora e diz se a identidade ACABOU de passar do limiar. */
	public Decision record(String identity) {
		return windowed(identity, clock.millis());
	}

	/**
	 * Conta a recusa no instante informado. {@code alert} é verdadeiro só na primeira recusa que
	 * levou a identidade ao limiar dentro da janela — as seguintes não repetem o aviso até a janela
	 * esvaziar.
	 */
	public Decision windowed(String identity, long now) {
		String key = identity == null || identity.isBlank() ? "anon:-" : identity;
		if (windows.size() >= MAX_IDENTITIES && !windows.containsKey(key)) {
			// Teto: identidade nova em mapa cheio não entra. A resposta ao cliente não muda; o que se
			// perde é o alerta agregado de uma identidade que nem chegou a repetir.
			return new Decision(false, 0);
		}
		Stamps stamps = windows.computeIfAbsent(key, ignored -> new Stamps());
		synchronized (stamps) {
			stamps.marks.addLast(now);
			while (!stamps.marks.isEmpty() && now - stamps.marks.peekFirst() > windowMs) {
				stamps.marks.pollFirst();
			}
			int inWindow = stamps.marks.size();
			if (inWindow < threshold || now - stamps.lastAlertAt < windowMs) {
				return new Decision(false, inWindow);
			}
			stamps.lastAlertAt = now;
			return new Decision(true, inWindow);
		}
	}

	/** Quantas identidades passaram do limiar agora — o painel usa junto com a trilha. */
	public int aboveThreshold() {
		long now = clock.millis();
		int count = 0;
		for (Stamps stamps : windows.values()) {
			if (inWindow(stamps, now) >= threshold) {
				count++;
			}
		}
		return count;
	}

	/** Recusas da identidade dentro da janela, sem contar a consulta como uma recusa nova. */
	public int inWindow(String identity, long now) {
		Stamps stamps = identity == null ? null : windows.get(identity);
		return stamps == null ? 0 : inWindow(stamps, now);
	}

	private int inWindow(Stamps stamps, long now) {
		int count = 0;
		synchronized (stamps) {
			for (long mark : stamps.marks) {
				if (now - mark <= windowMs) {
					count++;
				}
			}
		}
		return count;
	}

	/** Resultado de uma contagem: o aviso deve sair agora, e quanto há na janela. */
	public record Decision(boolean alert, int countInWindow) {
	}

	private static final class Stamps {

		private final Deque<Long> marks = new ArrayDeque<>();
		private long lastAlertAt = Long.MIN_VALUE / 2;
	}
}