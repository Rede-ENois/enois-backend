package com.socialcoisa.rede.termometro.application.port.out;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * A leitura de uma janela por CHAVE, não por número de página: cada lote começa no instante da
 * última linha lida (inclusivo) e a consulta desempata pelo id — a chave é `(instante asc, id
 * asc)`. A página antiga repetia a página 0 a cada lote: quem lia mais de uma página relia as
 * mesmas manifestações e nunca chegava ao fim do intervalo. O intervalo vazio custa uma consulta,
 * e o freio de segurança da leitura inteira continua valendo.
 */
public final class ChaveDaJanela {

	private ChaveDaJanela() {
	}

	/** O cursor da leitura: o instante (inclusivo) e, nele, o id a partir do qual ler. */
	public record Chave(Instant instante, String id) {

		public static Chave inicio(Instant de) {
			return new Chave(de, "");
		}

	}

	/** De onde a próxima página começa, com o tamanho da página. */
	@FunctionalInterface
	public interface Busca<T> {

		List<T> de(Chave chave, int tamanho);

	}

	/**
	 * Lê a janela em lotes de `tamanho`, no máximo `limite` consultas, e converte cada linha em
	 * manifestação com `linha` (que devolve null para o que não vale: texto em branco). A leitura
	 * para no lote curto, no freio de segurança — ou quando a chave não anda de um lote ao outro
	 * (a ordem é por instante e o empate no instante se resolve pelo id).
	 */
	public static <T, M> List<M> ler(Instant de, int tamanho, int limite, Busca<T> busca,
			Function<T, Chave> chave, Function<T, M> linha) {
		List<M> manifestacoes = new ArrayList<>();
		Chave cursor = Chave.inicio(de);
		for (int consulta = 0; consulta < limite; consulta++) {
			List<T> lote = busca.de(cursor, tamanho);
			if (lote.isEmpty()) {
				break;
			}
			// A próxima leitura recomeça no instante da última linha (o mesmo instante é relido,
			// o que é barato: o worker pula a publicação que já tem fatia feita).
			Chave seguinte = cursor;
			for (T item : lote) {
				Chave daLinha = chave.apply(item);
				if (!daLinha.instante().isBefore(seguinte.instante())) {
					seguinte = daLinha;
				}
				M convertida = linha.apply(item);
				if (convertida != null) {
					manifestacoes.add(convertida);
				}
			}
			if (lote.size() < tamanho) {
				break;
			}
			if (seguinte.equals(cursor)) {
				break;
			}
			cursor = seguinte;
		}
		return manifestacoes;
	}

}
