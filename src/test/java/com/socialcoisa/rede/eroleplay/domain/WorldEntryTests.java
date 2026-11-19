package com.socialcoisa.rede.eroleplay.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.shared.web.ApiException;

/** As regras da entrada de mundo, no domínio: título, corpo, tags e quem manda. */
class WorldEntryTests {

	private static final Instant AGORA = Instant.parse("2026-11-15T12:00:00Z");


	@Test
	void títuloObrigatórioComTeto() {
		assertThatThrownBy(() -> WorldEntry.create("camp", WorldKind.LORE, "  ", null, null, null, "autor", AGORA))
			.isInstanceOfSatisfying(ApiException.class,
				e -> assertThat(e.code()).isEqualTo("WORLD_TITLE_REQUIRED"));
		assertThatThrownBy(() -> WorldEntry.create("camp", WorldKind.LORE, "t".repeat(121), null, null, null,
			"autor", AGORA))
			.isInstanceOfSatisfying(ApiException.class,
				e -> assertThat(e.code()).isEqualTo("WORLD_TITLE_TOO_LONG"));
	}

	@Test
	void corpoComTetoEVazioViraNulo() {
		assertThatThrownBy(() -> WorldEntry.create("camp", WorldKind.LORE, "Torre", "x".repeat(20001), null, null,
			"autor", AGORA))
			.isInstanceOfSatisfying(ApiException.class,
				e -> assertThat(e.code()).isEqualTo("WORLD_BODY_TOO_LONG"));
		WorldEntry entrada = WorldEntry.create("camp", WorldKind.LORE, "Torre", "   ", null, null, "autor", AGORA);
		assertThat(entrada.body()).isNull();
	}

	@Test
	void tagsViramListaLimpaSemRepetiçãoEComTeto() {
		WorldEntry entrada = WorldEntry.create("camp", WorldKind.LOCALE, "Cidade",
			null, List.of("  Porto ", "porto", "", "mar"), null, "autor", AGORA);
		assertThat(entrada.tags()).containsExactly("porto", "mar");

		assertThatThrownBy(() -> WorldEntry.create("camp", WorldKind.LOCALE, "Cidade", null,
			List.of("t".repeat(300), "u".repeat(300)), null, "autor", AGORA))
			.isInstanceOfSatisfying(ApiException.class,
				e -> assertThat(e.code()).isEqualTo("WORLD_TAGS_TOO_LONG"));
	}

	@Test
	void ediçãoReescreveContenidoPreservaIdentidadeEAutoria() {
		WorldEntry original = WorldEntry.create("camp", WorldKind.PLOT, "Pista do anel", "numa gaveta",
			List.of("pista"), null, "jogador", AGORA);
		WorldEntry editada = original.edit(WorldKind.LORE, "Pista do anel — reescrita", "sob o piso",
			List.of("lore", "pista"), null, AGORA.plusSeconds(60));
		assertThat(editada.id()).isEqualTo(original.id());
		assertThat(editada.authorId()).isEqualTo("jogador");
		assertThat(editada.createdAt()).isEqualTo(AGORA);
		assertThat(editada.updatedAt()).isEqualTo(AGORA.plusSeconds(60));
		assertThat(editada.kind()).isEqualTo(WorldKind.LORE);
		assertThat(editada.tags()).containsExactly("lore", "pista");
		assertThatThrownBy(() -> original.edit(null, "sem tipo", null, null, null, AGORA))
			.isInstanceOfSatisfying(ApiException.class,
				e -> assertThat(e.code()).isEqualTo("WORLD_KIND_REQUIRED"));
	}

	@Test
	void regraDeQuemEditaEApaga() {
		WorldEntry doJogador = WorldEntry.create("camp", WorldKind.ITEM, "Chave", null, null, null, "jogador", AGORA);
		assertThat(doJogador.canBeEditedBy("jogador", false)).isTrue();
		assertThat(doJogador.canBeEditedBy("outro", false)).isFalse();
		assertThat(doJogador.canBeEditedBy("outro", true)).isTrue();
	}

}
