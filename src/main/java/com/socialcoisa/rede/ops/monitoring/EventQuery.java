package com.socialcoisa.rede.ops.monitoring;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Busca booleana simples para o painel ("500 route:/feed -health", {@code "pilha exata"}).
 *
 * <p>Gramática mínima: termos separados por espaço são AND; {@code -termo} nega;
 * {@code a OR b} une alternativas (basta um lado casar); aspas agrupam a frase exata. Tudo
 * minúsculo na comparação. Termo vazio casa com tudo.</p>
 */
final class EventQuery {

	private final List<Clause> required;
	private final List<List<String>> alternatives;
	private final List<String> excluded;

	private EventQuery(List<Clause> required, List<List<String>> alternatives, List<String> excluded) {
		this.required = required;
		this.alternatives = alternatives;
		this.excluded = excluded;
	}

	static EventQuery parse(String raw) {
		if (raw == null || raw.isBlank()) {
			return new EventQuery(List.of(), List.of(), List.of());
		}
		List<String> tokens = tokenize(raw.trim());
		List<Clause> required = new ArrayList<>();
		List<List<String>> alternatives = new ArrayList<>();
		List<String> excluded = new ArrayList<>();
		List<List<String>> sides = splitOr(tokens);
		if (sides.size() == 1) {
			for (String token : sides.get(0)) {
				if (token.equalsIgnoreCase("AND")) {
					continue;
				}
				if (token.startsWith("-") && token.length() > 1) {
					excluded.add(token.substring(1).toLowerCase(Locale.ROOT));
				}
				else {
					required.add(new Clause(token.toLowerCase(Locale.ROOT)));
				}
			}
			return new EventQuery(List.copyOf(required), List.of(), List.copyOf(excluded));
		}
		for (List<String> side : sides) {
			List<String> terms = new ArrayList<>();
			for (String token : side) {
				if (token.equalsIgnoreCase("AND")) {
					continue;
				}
				if (token.startsWith("-") && token.length() > 1) {
					excluded.add(token.substring(1).toLowerCase(Locale.ROOT));
				}
				else {
					terms.add(token.toLowerCase(Locale.ROOT));
				}
			}
			if (!terms.isEmpty()) {
				alternatives.add(List.copyOf(terms));
			}
		}
		return new EventQuery(List.of(), List.copyOf(alternatives), List.copyOf(excluded));
	}

	boolean matches(String haystack) {
		String hay = haystack == null ? "" : haystack;
		for (String term : excluded) {
			if (hay.contains(term)) {
				return false;
			}
		}
		for (Clause clause : required) {
			if (!hay.contains(clause.term())) {
				return false;
			}
		}
		if (!alternatives.isEmpty()) {
			boolean hit = false;
			for (List<String> side : alternatives) {
				boolean sideHit = true;
				for (String term : side) {
					if (!hay.contains(term)) {
						sideHit = false;
						break;
					}
				}
				if (sideHit) {
					hit = true;
					break;
				}
			}
			if (!hit) {
				return false;
			}
		}
		return true;
	}

	/** Divide por OR de topo (fora de aspas — o tokenizer já removeu as aspas agrupadas). */
	private static List<List<String>> splitOr(List<String> tokens) {
		List<List<String>> sides = new ArrayList<>();
		List<String> current = new ArrayList<>();
		for (String token : tokens) {
			if (token.equalsIgnoreCase("OR")) {
				sides.add(current);
				current = new ArrayList<>();
			}
			else {
				current.add(token);
			}
		}
		sides.add(current);
		return sides;
	}

	private static List<String> tokenize(String raw) {
		List<String> tokens = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		boolean quoted = false;
		for (int i = 0; i < raw.length(); i++) {
			char c = raw.charAt(i);
			if (c == '"') {
				quoted = !quoted;
				continue;
			}
			if (Character.isWhitespace(c) && !quoted) {
				if (current.length() > 0) {
					tokens.add(current.toString());
					current.setLength(0);
				}
				continue;
			}
			current.append(c);
		}
		if (current.length() > 0) {
			tokens.add(current.toString());
		}
		return tokens;
	}

	private record Clause(String term) {
	}
}
