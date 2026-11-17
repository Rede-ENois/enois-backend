package com.socialcoisa.rede.eroleplay.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.shared.web.ApiException;

/**
 * As regras da cópia pública: o que não pode entrar, o que não pode passar do teto, e o que a
 * publicação preserva de si mesma quando é republicada.
 */
class LibrarySheetTests {

	private final Instant now = Instant.parse("2026-11-15T12:00:00Z");

	private Map<String, String> fields(String... pairs) {
		Map<String, String> map = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2) {
			map.put(pairs[i], pairs[i + 1]);
		}
		return map;
	}

	@Test
	void publicaNasceComIdentidadePropriaEDadosMinimos() {
		LibrarySheet sheet = LibrarySheet.publish("camp_1", "sheet_1", "user_1", "Aria do Fluxo", "d20",
			fields("origem", "Ókulo"), now);
		assertThat(sheet.id()).startsWith("roleplay_library_");
		assertThat(sheet.campaignId()).isEqualTo("camp_1");
		assertThat(sheet.sheetId()).isEqualTo("sheet_1");
		assertThat(sheet.characterName()).isEqualTo("Aria do Fluxo");
		assertThat(sheet.gameSystem()).isEqualTo("d20");
		assertThat(sheet.fields()).containsEntry("origem", "Ókulo");
		assertThat(sheet.publishedAt()).isEqualTo(now);
		assertThat(sheet.updatedAt()).isEqualTo(now);
	}

	@Test
	void campoVazioNaoEntraENomeObrigatorio() {
		assertThatThrownBy(() -> LibrarySheet.publish("c", "s", "u", "  ", "d20", fields("a", "1"), now))
			.isInstanceOfSatisfying(ApiException.class,
				e -> assertThat(e.code()).isEqualTo("LIBRARY_NAME_REQUIRED"));
		assertThatThrownBy(() -> LibrarySheet.publish("c", "s", "u", "Aria", " ", fields("a", "1"), now))
			.isInstanceOfSatisfying(ApiException.class,
				e -> assertThat(e.code()).isEqualTo("LIBRARY_SYSTEM_REQUIRED"));
		// Valor vazio é descartado, não publicado: campo sem conteúdo não vira linha vazia na API.
		assertThat(LibrarySheet.publish("c", "s", "u", "Aria", "d20",
			fields("vazio", "", "cheio", "x"), now).fields()).containsOnlyKeys("cheio");
	}

	@Test
	void tetoDeNomeEDeCampos() {
		assertThatThrownBy(() -> LibrarySheet.publish("c", "s", "u", "n".repeat(121), "d20", fields("a", "1"), now))
			.isInstanceOfSatisfying(ApiException.class,
				e -> assertThat(e.code()).isEqualTo("LIBRARY_NAME_TOO_LONG"));
		Map<String, String> grandes = new LinkedHashMap<>();
		for (int i = 0; i < 201; i++) {
			grandes.put("campo" + i, "x");
		}
		assertThatThrownBy(() -> LibrarySheet.publish("c", "s", "u", "Aria", "d20", grandes, now))
			.isInstanceOfSatisfying(ApiException.class,
				e -> assertThat(e.code()).isEqualTo("LIBRARY_FIELDS_TOO_MANY"));
		assertThatThrownBy(() -> LibrarySheet.publish("c", "s", "u", "Aria", "d20", fields("chave".repeat(20), "x"),
			now)).isInstanceOfSatisfying(ApiException.class,
				e -> assertThat(e.code()).isEqualTo("LIBRARY_FIELD_TOO_LONG"));
	}

	@Test
	void semNadaAbertoNaoPublica() {
		// A ficha 100% privada chega aqui com o mapa vazio: publicar seria mentir que há conteúdo.
		assertThatThrownBy(() -> LibrarySheet.publish("c", "s", "u", "Aria", "d20", Map.of(), now))
			.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("LIBRARY_EMPTY"));
	}

	@Test
	void restorePreservaEPublicadoTemCamposDefensivos() {
		LibrarySheet original = LibrarySheet.publish("c", "s", "u", "Aria", "d20", fields("a", "1"), now);
		LibrarySheet copy = LibrarySheet.restore(original.id(), original.campaignId(), original.sheetId(),
			original.ownerId(), original.characterName(), original.gameSystem(), original.fields(), now,
			now.plusSeconds(60));
		assertThat(copy.id()).isEqualTo(original.id());
		assertThat(copy.publishedAt()).isEqualTo(now);
		assertThat(copy.updatedAt()).isEqualTo(now.plusSeconds(60));
		// O mapa devolvido é cópia: quem mexe na leitura não contamina o domínio.
		copy.fields().put("injecao", "x");
		assertThat(original.fields()).doesNotContainKey("injecao");
	}
}
