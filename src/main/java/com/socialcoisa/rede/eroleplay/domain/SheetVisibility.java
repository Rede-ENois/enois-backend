package com.socialcoisa.rede.eroleplay.domain;

import java.util.Locale;

import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Quem enxerga UM campo da ficha.
 *
 * <p>A ficha não é uma coisa só: o nome do personagem a mesa inteira conhece, o histórico o mestre
 * lê, e o segredo que o jogador escreveu não sai dele. Por isso a privacidade é por CAMPO, e o
 * modelo do mestre define o máximo de abertura de cada um. O jogador pode fechar mais, nunca abrir
 * além do que o mestre autorizou.</p>
 *
 * <p>É uma escada: {@link #PRIVATE} ⊂ {@link #GAME_MASTER} ⊂ {@link #CAMPAIGN}. Comparar por posição é
 * o que permite afirmar "esta visibilidade cabe naquela" sem uma tabela de combinações que
 * envelhece.</p>
 */
public enum SheetVisibility {

	/** Só quem preencheu. Nem o mestre lê: é o segredo do jogador. */
	PRIVATE,

	/** Quem preencheu e o mestre da campanha. É o padrão do modelo: o mestre precisa da ficha para mestrar. */
	GAME_MASTER,

	/** Quem preencheu, o mestre e os demais participantes da campanha. */
	CAMPAIGN;

	/** Se esta visibilidade é tão ou mais aberta que a informada. */
	public boolean reaches(SheetVisibility other) {
		return ordinal() >= other.ordinal();
	}

	/**
	 * Interpreta o nome informado na API. Nome desconhecido vira erro explícito: uma ficha não pode
	 * ser gravada com uma visibilidade que o produto não sabe aplicar.
	 */
	public static SheetVisibility of(String name) {
		if (name == null || name.isBlank()) {
			return GAME_MASTER;
		}
		try {
			return valueOf(name.trim().toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException error) {
			throw new ApiException(400, "INVALID_SHEET_VISIBILITY", "Visibilidade de campo desconhecida.");
		}
	}
}