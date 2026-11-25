package com.socialcoisa.rede.feed.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * A lista de ícones é POSICIONAL: o ícone i é o do pacote na posição i (o front desenha a reação i com
 * a figura i no CSS). Os testes do {@link ReactionType} e do {@link ReactionIcons} conferem cada tipo
 * pelo nome; este confere a ORDEM, que `codigos()` (mapa de inserção) e o enum podem desalinhar sem
 * nenhum tipo ficar sem ícone.
 */
class ReactionIconOrderTests {

	/**
	 * As 76 expressões do pacote É Nóis, na ordem do enum (e do front, em `public/emojis/enois/`).
	 * O coração roxo não está aqui de propósito: no pacote ele é figura a mais, e o enum o põe no
	 * FIM — depois de `salute` — para a fileira desenhar o joinha ao lado dele.
	 */
	private static final List<String> SEM_CORACAO = List.of("like", "love", "joy", "sparkle", "wow", "angry", "cool",
			"cry", "sob", "sleep", "think", "nerd", "party", "confetti", "coffee", "selfie", "thermometer", "pirate",
			"hero", "zany", "scream", "grimace", "melting", "pleading", "eyes", "mindblown", "vomit", "nauseated",
			"skull", "eyeroll", "rocket", "wizard", "chef", "surf", "magic", "rebel", "wink", "grin", "rage", "mad",
			"money", "fear", "wave", "peace", "ok", "rock", "dislike", "clap", "raised", "pray", "gasp", "hug",
			"mask", "cowboy", "smirk", "ghost", "poop", "robot", "alien", "monocle", "vulcan", "crossed",
			"point_right", "fistbump", "muscle", "starstruck", "tongue", "kiss", "drool", "insightful", "note",
			"fire", "clown", "nails", "useful", "salute");

	/** O coração roxo fecha a lista: é a última reação e o último ícone do mapa. */
	private static final String CORACAO_ROXO = "heart_purple";

	/** O que `codigos()` tem de devolver: o pacote inteiro e o coração roxo no fim. */
	private static final List<String> ESPERADO = Stream.concat(SEM_CORACAO.stream(), Stream.of(CORACAO_ROXO)).toList();

	@Test
	void cadaTipoLeOIconeEEsseIconeFicaNaPosicaoDeleNoEnum() {
		List<String> naOrdemDoEnum = Arrays.stream(ReactionType.values()).map(ReactionIcons::codigo).toList();

		assertThat(naOrdemDoEnum).containsExactlyElementsOf(ESPERADO);
	}

	/**
	 * A armadilha é o mapa: `codigos()` anda na ordem de INSERÇÃO, não na do enum. Mover uma linha
	 * do bloco estático (o coração roxo no meio, por exemplo) desloca as figuras sem nenhum tipo
	 * ficar sem ícone — só a fileira fica errada.
	 */
	@Test
	void codigosAndaNaOrdemDoEnumComOCoracaoRoxoNoFim() {
		assertThat(ReactionIcons.codigos()).containsExactlyElementsOf(ESPERADO);
	}
}
