package com.socialcoisa.rede.network.domain;

import java.util.Collection;
import java.util.Locale;

/**
 * Comparação de região, num lugar só: a grafia normalizada é a chave de tudo que decide por região.
 *
 * <p>O comparador de afinidade sempre foi case-insensitive ({@code equalsIgnoreCase}, ADR-012), e a
 * declaração de região fora de operação (V304) usa a mesma regra. Sem normalizar, "Sudeste" no nó e
 * "sudeste" na declaração seriam a mesma região para o balanceador e duas para o evacuador — o nó
 * receberia tarefa de uma região declarada caída.</p>
 *
 * <p>Região ausente é <b>nunca</b> igual a região declarada: quem não declarou região não pertence
 * à região que caiu.</p>
 */
public final class NetworkRegions {

	private NetworkRegions() {
	}

	/** Minúsculas, sem espaços nas pontas; {@code null} vira string vazia. */
	public static String normalize(String region) {
		return region == null ? "" : region.trim().toLowerCase(Locale.ROOT);
	}

	/** As duas regiões são a mesma, ignorando caixa e espaços nas pontas? */
	public static boolean same(String left, String right) {
		String a = normalize(left);
		String b = normalize(right);
		return !a.isEmpty() && a.equals(b);
	}

	/**
	 * A região está entre as declaradas fora de operação? A coleção já vem normalizada (é o que o
	 * port devolve), e a comparação normaliza o candidato.
	 */
	public static boolean outOfService(Collection<String> declared, String region) {
		String normalized = normalize(region);
		return !normalized.isEmpty() && declared.contains(normalized);
	}

	/**
	 * A região está atendida por algum nó? Usado para separar "a tarefa pediu esta região" de "a
	 * tarefa pediu uma região que não existe mais na rede".
	 */
	public static boolean sameAsAny(Collection<String> regions, String region) {
		String normalized = normalize(region);
		if (normalized.isEmpty()) {
			return false;
		}
		for (String candidate : regions) {
			if (normalized.equals(normalize(candidate))) {
				return true;
			}
		}
		return false;
	}
}