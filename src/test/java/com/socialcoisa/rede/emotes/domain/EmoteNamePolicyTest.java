package com.socialcoisa.rede.emotes.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EmoteNamePolicyTest {

	@Test
	void bloqueiaOsNomesPedidos() {
		assertThat(EmoteNamePolicy.blocked("poggies")).isTrue();
		assertThat(EmoteNamePolicy.blocked("booty")).isTrue();
		assertThat(EmoteNamePolicy.blocked("butt")).isTrue();
		assertThat(EmoteNamePolicy.blocked("egg")).isTrue();
		assertThat(EmoteNamePolicy.blocked("plant")).isTrue();
		assertThat(EmoteNamePolicy.blocked("show")).isTrue();
		assertThat(EmoteNamePolicy.blocked("dick")).isTrue();
	}

	@Test
	void bloqueiaNomeParecidoENaoSoOExato() {
		// Caixa, separador, acento e letra trocada por numero caem na mesma regra.
		assertThat(EmoteNamePolicy.blocked("POGGIES")).isTrue();
		assertThat(EmoteNamePolicy.blocked("big-booty")).isTrue();
		assertThat(EmoteNamePolicy.blocked("booty_shake")).isTrue();
		assertThat(EmoteNamePolicy.blocked("D1CK")).isTrue();
		assertThat(EmoteNamePolicy.blocked("d1ckhead")).isTrue();
		assertThat(EmoteNamePolicy.blocked("p0rn")).isTrue();
		assertThat(EmoteNamePolicy.blocked("s3xy")).isTrue();
		assertThat(EmoteNamePolicy.blocked("Egg_Plant")).isTrue();
		assertThat(EmoteNamePolicy.blocked("peitos")).isTrue();
	}

	@Test
	void bloqueiaCompostoQueCarregaOTermoForte() {
		assertThat(EmoteNamePolicy.blocked("hugebutt")).isTrue();
		assertThat(EmoteNamePolicy.blocked("hentaiHug")).isTrue();
	}

	@Test
	void naoBloqueiaFigurinhaInnocua() {
		assertThat(EmoteNamePolicy.blocked("KEKW")).isFalse();
		assertThat(EmoteNamePolicy.blocked("OMEGALUL")).isFalse();
		assertThat(EmoteNamePolicy.blocked("catJAM")).isFalse();
		assertThat(EmoteNamePolicy.blocked("button")).isFalse();
		assertThat(EmoteNamePolicy.blocked("butterfly")).isFalse();
		// Termo fraco so casa como palavra inteira: composto innocuo passa.
		assertThat(EmoteNamePolicy.blocked("showdown")).isFalse();
		assertThat(EmoteNamePolicy.blocked("plantfood")).isFalse();
		assertThat(EmoteNamePolicy.blocked("Pog")).isFalse();
		assertThat(EmoteNamePolicy.blocked(null)).isFalse();
		assertThat(EmoteNamePolicy.blocked("")).isFalse();
	}

}
