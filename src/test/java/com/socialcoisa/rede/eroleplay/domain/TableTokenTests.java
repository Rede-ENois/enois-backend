package com.socialcoisa.rede.eroleplay.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Um token na mesa.
 *
 * <p>O que este teste tranca: nome e cor são obrigatórios com formato próprio; o grid é finito;
 * token de personagem move o dono, token de cenário só o mestre — a regra de quem move é do
 * domínio, não do controller.</p>
 */
class TableTokenTests {

	private static final Instant NOW = Instant.parse("2026-11-14T10:00:00Z");
	private static final Instant LATER = Instant.parse("2026-11-14T10:05:00Z");

	@Test
	void tokenPrecisaDeNomeEdeCorHexValida() {
		assertThatThrownBy(() -> create("  ", "#FF0000"))
			.isInstanceOfSatisfying(ApiException.class, e -> {
				assertThat(e.status()).isEqualTo(400);
				assertThat(e.code()).isEqualTo("TOKEN_LABEL_REQUIRED");
			});
		assertThatThrownBy(() -> create("nome gigante demais".repeat(8), "#FF0000"))
			.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("TOKEN_LABEL_TOO_LONG"));
		assertThatThrownBy(() -> create("goblin", "vermelho"))
			.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("TOKEN_COLOR_INVALID"));
		assertThatThrownBy(() -> create("goblin", "#GG0000"))
			.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("TOKEN_COLOR_INVALID"));

		// Com e sem o "#", 6 e 8 dígitos são o contrato.
		assertThat(create("goblin", "#FF0000").color()).isEqualTo("#FF0000");
		assertThat(create("goblin", "00ff00").color()).isEqualTo("00ff00");
		assertThat(create("goblin", "#1A2B3C4D").color()).isEqualTo("#1A2B3C4D");
	}

	@Test
	void gridEFinito() {
		assertThatThrownBy(() -> create("goblin", "#FF0000", 10_000, 0))
			.isInstanceOfSatisfying(ApiException.class, e -> {
				assertThat(e.status()).isEqualTo(400);
				assertThat(e.code()).isEqualTo("TOKEN_POSITION_INVALID");
			});
		assertThatThrownBy(() -> create("goblin", "#FF0000", 0, -10_000))
			.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("TOKEN_POSITION_INVALID"));
		assertThat(create("goblin", "#FF0000", -9_999, 9_999).posX()).isEqualTo(-9_999);
	}

	@Test
	void moverAtualizaPosicaoENaoAMatricula() {
		TableToken token = create("goblin", "#FF0000", 0, 0);

		TableToken moved = token.moveTo(3, -4, LATER);

		assertThat(moved.posX()).isEqualTo(3);
		assertThat(moved.posY()).isEqualTo(-4);
		assertThat(moved.updatedAt()).isEqualTo(LATER);
		assertThat(moved.createdAt()).isEqualTo(token.createdAt());
		assertThat(moved.id()).isEqualTo(token.id());
	}

	@Test
	void tokenDePersonagemMoveODonoETokenDeCenarioSOMestre() {
		TableToken personagem = create("elena", "#00FF00", 0, 0, "u_jogadora");
		TableToken cenario = create("porta", "#808080", 1, 1, null);

		assertThat(personagem.isOwned()).isTrue();
		assertThat(cenario.isOwned()).isFalse();

		assertThat(personagem.canBeMovedBy("u_jogadora", false)).isTrue();
		assertThat(personagem.canBeMovedBy("outro", false)).isFalse();
		assertThat(cenario.canBeMovedBy("u_jogadora", false)).isFalse();
		// O mestre move qualquer um, inclusive o cenário.
		assertThat(personagem.canBeMovedBy("u_mestre", true)).isTrue();
		assertThat(cenario.canBeMovedBy("u_mestre", true)).isTrue();
	}

	private static TableToken create(String label, String color) {
		return create(label, color, 0, 0, null);
	}

	private static TableToken create(String label, String color, int x, int y) {
		return create(label, color, x, y, null);
	}

	private static TableToken create(String label, String color, int x, int y, String owner) {
		return TableToken.create("roleplay_token_1", "mesa_1", "camp_1", owner, label, color, x, y, NOW);
	}
}
