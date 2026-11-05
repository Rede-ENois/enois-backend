package com.socialcoisa.rede.feed.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Os limites por tipo: Divertido é curto (400) e o Momento também (500, com prazo de 24h). */
class PostContentLimitsTests {

	@Test
	void divertidoIs400AndMomentIs500() {
		assertThat(PostContentLimits.of(PostType.DIVERTIDO)).isEqualTo(400);
		assertThat(PostContentLimits.of(PostType.MOMENT)).isEqualTo(500);
		// Duvida tecnica precisa de espaco para codigo: mesmo limite do post comum.
		assertThat(PostContentLimits.of(PostType.QUESTION)).isEqualTo(1_200);
		assertThat(PostContentLimits.of(PostType.TUTORIAL)).isEqualTo(5_000);
		assertThat(PostContentLimits.of(null)).isEqualTo(1_200);
	}
}
