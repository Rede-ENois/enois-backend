package com.socialcoisa.rede.feed.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

/**
 * A lista de reações é a do pacote É Nóis: 77 expressões, sem repetição e sem tirar nenhuma das
 * que já existiam. O front mostra exatamente este conjunto (REACTION_TYPES), e a ordem é a do
 * seletor — as originais primeiro, as novas no fim.
 */
class ReactionTypeTests {

	/** As 26 reações que existiam antes das expressões novas: não podem sumir nem mudar de nome. */
	private static final String[] ORIGINAIS = { "LIKE", "LOVE", "LAUGH", "ROFL", "SKULL", "WOW", "SAD", "SOB",
			"CELEBRATE", "SUPPORT", "INSIGHTFUL", "ANGRY", "DISGUST", "SKEPTICAL", "EYES", "NOTE", "FIRE", "CLAP",
			"CLOWN", "NAILS", "USEFUL", "RELATABLE", "HYPE", "THANKS", "VIBE", "SALUTE" };

	@Test
	void temAsSetentaESeteExpressoesDoPacoteEnois() {
		assertThat(ReactionType.values()).hasSize(77);
	}

	@Test
	void aUltimaEraOGhostEAgoraOCoracaoRoxoFicaNoFim() {
		// Reação nova entra no FIM do enum: o tipo é gravado pelo nome, e a posição só diz quando
		// ela nasceu.
		String[] nomes = Arrays.stream(ReactionType.values()).map(Enum::name).toArray(String[]::new);

		assertThat(nomes[nomes.length - 2]).isEqualTo("GHOST");
		assertThat(nomes[nomes.length - 1]).isEqualTo("HEART_PURPLE");
	}

	@Test
	void asOriginaisContinuamNoComecoENaMesmaOrdem() {
		String[] nomes = Arrays.stream(ReactionType.values()).map(Enum::name).toArray(String[]::new);

		// Os 26 primeiros são as reações antigas, na ordem antiga. A ordem importa para o seletor (e
		// mudá-la não mexe no banco, que grava o nome — mas o usuário veria a lista mudar à toa).
		assertThat(Arrays.copyOf(nomes, ORIGINAIS.length)).containsExactly(ORIGINAIS);
		// Fim do enum = documento do que entrou depois: a partir daqui é só acréscimo.
		assertThat(nomes[nomes.length - 1]).isEqualTo("HEART_PURPLE");
	}
}