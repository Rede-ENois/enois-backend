package com.socialcoisa.rede.wrapped.domain;

import java.util.List;

/**
 * Como a presença da conta mudou ao longo do ano: a atividade por trimestre e, quando a segunda
 * metade tem pelo menos o dobro (ou menos da metade) da primeira, o sentido da mudança.
 *
 * <p>Nada é interpretado como qualidade: "cresceu" ou "desacelerou" são só volume. A view mostra a
 * série e, quando houver mudança clara, uma frase neutra. Sem atividade nas duas metades, o sentido
 * é {@link Sentido#ESTAVEL} para a view não dizer nada que não existe.</p>
 */
public record WrappedComportamento(List<Integer> porTrimestre, Sentido sentido) {

	public enum Sentido {
		CRESCEU,
		DESACELEROU,
		ESTAVEL
	}

	/** Deriva o sentido da mudança entre a primeira e a segunda metade do ano. */
	public static Sentido sentido(List<Integer> porTrimestre) {
		if (porTrimestre == null || porTrimestre.size() != 4) {
			return Sentido.ESTAVEL;
		}
		int primeira = porTrimestre.get(0) + porTrimestre.get(1);
		int segunda = porTrimestre.get(2) + porTrimestre.get(3);
		if (primeira == 0 && segunda == 0) {
			return Sentido.ESTAVEL;
		}
		if (primeira == 0) {
			return Sentido.CRESCEU;
		}
		if (segunda >= primeira * 2) {
			return Sentido.CRESCEU;
		}
		if (segunda * 2 <= primeira) {
			return Sentido.DESACELEROU;
		}
		return Sentido.ESTAVEL;
	}
}
