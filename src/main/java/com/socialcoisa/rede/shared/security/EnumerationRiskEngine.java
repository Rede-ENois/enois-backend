package com.socialcoisa.rede.shared.security;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Pontua o comportamento de uma origem (IP) e de uma identidade (usuário) para separar
 * <em>navegação</em> de <em>descoberta de recursos</em>.
 *
 * <p><b>Por que não é só volume.</b> O limitador global já conta requisições por IP, e mesmo assim
 * um script consegue varrer dezenas de rotas antes de bater no teto — e uma pessoa com várias abas
 * abertas produz volume parecido com o de um scanner lento. O que distingue os dois é a
 * <em>forma</em> do tráfego: quantos identificadores <strong>distintos</strong> foram pedidos, se
 * eles formam uma sequência previsível, e qual a proporção de recursos que não existem. Navegar
 * repete os mesmos poucos identificadores; enumerar pede um novo a cada requisição.</p>
 *
 * <p><b>O que este componente NÃO faz.</b> Não bloqueia nada sozinho. Ele devolve uma avaliação
 * (pontuação, motivos, nível) e quem decide é o filtro, que respeita o modo de operação. Em
 * {@code shadow} (o padrão) nada é negado: o evento é registrado e a pontuação aparece no painel,
 * para o time validar os limiares com tráfego real antes de ligar o bloqueio.</p>
 *
 * <p><b>Falsos positivos.</b> Rede compartilhada, NAT e VPN fazem muita gente dividir o mesmo IP.
 * Por isso: (a) a janela é curta e a pontuação decai; (b) o sinal de sequência exige
 * <em>ordem</em>, não só quantidade; (c) identidade autenticada tem balde próprio, então quem está
 * logado não é punido pelo vizinho de NAT; (d) rotas quentes da tela ({@code SHARED}) nunca contam
 * como enumeração; (e) o bloqueio automático é sempre temporário e progressivo.</p>
 */
@Component
public class EnumerationRiskEngine {

	private static final Logger LOG = LoggerFactory.getLogger(EnumerationRiskEngine.class);

	/** Quantas chaves manter antes de varrer as vencidas (proteção de memória). */
	private static final int MAX_KEYS = 50_000;

	/** Nível de resposta, do mais brando ao mais grave. */
	public enum Level {
		NONE, SUSPECT, HIGH, CONFIRMED, SEVERE
	}

	/** O que foi observado numa requisição, já classificado pela superfície. */
	public record Observation(String key, String routeLabel, EnumerationSurface.Kind kind, String identifier,
			boolean authenticated, int status) {
	}

	/** O veredito para esta requisição. */
	public record Assessment(Level level, int score, List<String> reasons, long distinctIdentifiers,
			double missingRatio, boolean sequential) {

		static Assessment none() {
			return new Assessment(Level.NONE, 0, List.of(), 0, 0, false);
		}

		public boolean atLeast(Level other) {
			return level.ordinal() >= other.ordinal();
		}
	}

	/** Janela deslizante de uma chave: identificadores distintos, sequência e erros. */
	private static final class Window {

		final Deque<Long> times = new ArrayDeque<>();
		final Deque<String> identifiers = new ArrayDeque<>();
		final Deque<Integer> statuses = new ArrayDeque<>();
		final Set<String> distinct = new HashSet<>();
		final Set<String> routes = new HashSet<>();
		int sequentialHits;
		String lastIdentifier;

		void trim(long now, long windowMs) {
			while (!times.isEmpty() && now - times.peekFirst() >= windowMs) {
				times.pollFirst();
				String old = identifiers.pollFirst();
				statuses.pollFirst();
				if (old != null) {
					distinct.remove(old);
				}
			}
		}

		void clear() {
			times.clear();
			identifiers.clear();
			statuses.clear();
			distinct.clear();
			routes.clear();
			sequentialHits = 0;
			lastIdentifier = null;
		}
	}

	private final Map<String, Window> windows = new ConcurrentHashMap<>();
	private final Clock clock;

	private final long windowMs;
	private final int distinctSuspect;
	private final int distinctHigh;
	private final int distinctConfirmed;
	private final int sequentialSuspect;
	private final double missingRatioHigh;
	private final int minSamplesForRatio;
	private final int routeSpreadHigh;

	public EnumerationRiskEngine(Clock clock,
			@Value("${rede.security.enumeration.window-seconds:120}") long windowSeconds,
			@Value("${rede.security.enumeration.distinct-suspect:12}") int distinctSuspect,
			@Value("${rede.security.enumeration.distinct-high:25}") int distinctHigh,
			@Value("${rede.security.enumeration.distinct-confirmed:50}") int distinctConfirmed,
			@Value("${rede.security.enumeration.sequential-suspect:4}") int sequentialSuspect,
			@Value("${rede.security.enumeration.missing-ratio-high:0.6}") double missingRatioHigh,
			@Value("${rede.security.enumeration.min-samples-for-ratio:8}") int minSamplesForRatio,
			@Value("${rede.security.enumeration.route-spread-high:4}") int routeSpreadHigh) {
		this.clock = clock;
		this.windowMs = Math.max(10, windowSeconds) * 1000L;
		this.distinctSuspect = Math.max(2, distinctSuspect);
		this.distinctHigh = Math.max(this.distinctSuspect, distinctHigh);
		this.distinctConfirmed = Math.max(this.distinctHigh, distinctConfirmed);
		this.sequentialSuspect = Math.max(2, sequentialSuspect);
		this.missingRatioHigh = Math.min(1.0, Math.max(0.0, missingRatioHigh));
		this.minSamplesForRatio = Math.max(2, minSamplesForRatio);
		this.routeSpreadHigh = Math.max(2, routeSpreadHigh);
	}

	/**
	 * Registra a observação e devolve o veredito. Chamado uma vez por requisição monitorada.
	 *
	 * <p>Rotas {@code SHARED} não entram na conta de enumeração: elas só existem para o teto de
	 * volume, que é outro filtro.</p>
	 */
	public Assessment observe(Observation observation) {
		if (observation == null || observation.kind() == EnumerationSurface.Kind.SHARED) {
			return Assessment.none();
		}
		long now = clock.millis();
		if (windows.size() > MAX_KEYS) {
			sweep(now);
		}
		Window window = windows.computeIfAbsent(observation.key(), key -> new Window());
		synchronized (window) {
			window.trim(now, windowMs);
			window.times.addLast(now);
			window.statuses.addLast(observation.status());
			window.routes.add(observation.routeLabel());

			String identifier = observation.identifier();
			if (identifier != null) {
				window.identifiers.addLast(identifier);
				window.distinct.add(identifier);
				if (window.lastIdentifier != null && isSequential(window.lastIdentifier, identifier)) {
					window.sequentialHits++;
				}
				window.lastIdentifier = identifier;
			}
			return assess(window);
		}
	}

	/** Avaliação sem registrar nada (diagnóstico e painel). */
	public Assessment peek(String key) {
		Window window = windows.get(key);
		if (window == null) {
			return Assessment.none();
		}
		synchronized (window) {
			window.trim(clock.millis(), windowMs);
			return assess(window);
		}
	}

	/** Zera a janela de uma chave (usado quando o bloqueio é aplicado ou liberado). */
	public void reset(String key) {
		Window window = windows.get(key);
		if (window != null) {
			synchronized (window) {
				window.clear();
			}
		}
	}

	private Assessment assess(Window window) {
		int distinct = window.distinct.size();
		int samples = window.statuses.size();
		long missing = window.statuses.stream().filter(status -> status == 404).count();
		double missingRatio = samples == 0 ? 0 : (double) missing / samples;
		boolean sequential = window.sequentialHits >= sequentialSuspect;
		int routeSpread = window.routes.size();

		List<String> reasons = new ArrayList<>();
		int score = 0;

		if (distinct >= distinctSuspect) {
			score += 20;
			reasons.add("identificadores distintos: " + distinct);
		}
		if (distinct >= distinctHigh) {
			score += 25;
			reasons.add("varredura ampla: " + distinct + " identificadores");
		}
		if (distinct >= distinctConfirmed) {
			score += 25;
			reasons.add("varredura confirmada: " + distinct + " identificadores");
		}
		if (sequential) {
			score += 25;
			reasons.add("sequência previsível de identificadores (" + window.sequentialHits + " saltos)");
		}
		if (samples >= minSamplesForRatio && missingRatio >= missingRatioHigh) {
			score += 20;
			reasons.add(String.format("recursos inexistentes: %.0f%% de %d", missingRatio * 100, samples));
		}
		if (routeSpread >= routeSpreadHigh) {
			score += 10;
			reasons.add("consulta distribuída em " + routeSpread + " rotas");
		}

		Level level = Level.NONE;
		if (score >= 80) {
			level = Level.CONFIRMED;
		}
		else if (score >= 55) {
			level = Level.HIGH;
		}
		else if (score >= 30) {
			level = Level.SUSPECT;
		}
		// Reincidência de varredura confirmada vira o nível mais grave.
		if (level == Level.CONFIRMED && sequential && missingRatio >= missingRatioHigh) {
			level = Level.SEVERE;
		}
		return new Assessment(level, score, List.copyOf(reasons), distinct, missingRatio, sequential);
	}

	/**
	 * Dois identificadores formam sequência quando são o mesmo prefixo com um número que anda de
	 * um em um (ou de dois em dois, para quem pula de par em par). É o padrão de quem itera um
	 * contador — não o de quem navega.
	 */
	static boolean isSequential(String previous, String current) {
		if (previous == null || current == null || previous.equals(current)) {
			return false;
		}
		int prefix = commonPrefix(previous, current);
		if (prefix == 0) {
			return false;
		}
		String left = previous.substring(prefix);
		String right = current.substring(prefix);
		if (left.isEmpty() || right.isEmpty() || !left.matches("\\d+") || !right.matches("\\d+")) {
			return false;
		}
		if (left.length() > 9 || right.length() > 9) {
			return false;
		}
		long delta = Math.abs(Long.parseLong(right) - Long.parseLong(left));
		return delta == 1 || delta == 2;
	}

	private static int commonPrefix(String left, String right) {
		int limit = Math.min(left.length(), right.length());
		int index = 0;
		while (index < limit && left.charAt(index) == right.charAt(index)) {
			index++;
		}
		return index;
	}

	private void sweep(long now) {
		windows.entrySet().removeIf(entry -> {
			Window window = entry.getValue();
			synchronized (window) {
				window.trim(now, windowMs);
				return window.times.isEmpty();
			}
		});
	}

	/** Quantas chaves estão sob observação agora (diagnóstico). */
	public int trackedKeys() {
		return windows.size();
	}

	/** Uma chave observada, com o veredito atual (painel administrativo). */
	public record Observed(String key, Assessment assessment) {
	}

	/**
	 * As chaves com pontuação acima de zero, da mais grave para a mais branda.
	 *
	 * <p>É o que o time revisa no modo shadow: quem está sendo pontuado, por quê e com que nível —
	 * sem que nada tenha sido bloqueado.</p>
	 */
	public List<Observed> observed(int limit) {
		long now = clock.millis();
		List<Observed> found = new ArrayList<>();
		windows.forEach((key, window) -> {
			synchronized (window) {
				window.trim(now, windowMs);
				if (window.times.isEmpty()) {
					return;
				}
				Assessment assessment = assess(window);
				if (assessment.level() != Level.NONE) {
					found.add(new Observed(key, assessment));
				}
			}
		});
		found.sort((left, right) -> Integer.compare(right.assessment().score(), left.assessment().score()));
		return found.size() > limit ? List.copyOf(found.subList(0, Math.max(0, limit))) : List.copyOf(found);
	}

	/** Loga o veredito no formato dos eventos de segurança. */
	public void log(String event, String key, String route, Assessment assessment) {
		if (assessment.level() == Level.NONE) {
			return;
		}
		LOG.warn("{} key={} route={} level={} score={} distinct={} missingRatio={} reasons={}", event, key, route,
			assessment.level(), assessment.score(), assessment.distinctIdentifiers(),
			String.format("%.2f", assessment.missingRatio()), assessment.reasons());
	}
}
