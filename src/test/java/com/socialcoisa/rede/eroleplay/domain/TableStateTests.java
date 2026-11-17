package com.socialcoisa.rede.eroleplay.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.shared.web.ApiException;

/**
 * O estado vivo da mesa.
 *
 * <p>O que este teste tranca: a ordem de fala não repete pessoa e tem teto; quem sai da ordem perde
 * a vez; passar a vez exige quem esteja na ordem (quando a ordem existe); a cena tem teto e texto
 * vazio é ausência de cena, não string vazia gravada.</p>
 */
class TableStateTests {

	private static final Instant NOW = Instant.parse("2026-11-14T10:00:00Z");
	private static final Instant LATER = Instant.parse("2026-11-14T10:05:00Z");

	@Test
	void estadoNasceVazioEComOIDaPropriaMesa() {
		TableState state = TableState.empty("mesa_1", "camp_1", NOW);

		assertThat(state.id()).isEqualTo("mesa_1");
		assertThat(state.tableId()).isEqualTo("mesa_1");
		assertThat(state.campaignId()).isEqualTo("camp_1");
		assertThat(state.turnOrder()).isEmpty();
		assertThat(state.activeTurnUserId()).isNull();
		assertThat(state.scene()).isNull();
	}

	@Test
	void ordemDeFalaNaoRepetePessoaETemTeto() {
		TableState state = TableState.empty("mesa_1", "camp_1", NOW);
		List<String> repetida = new ArrayList<>(List.of("u1", "u2", "u1"));

		TableState comOrdem = state.withTurnOrder(repetida, LATER);

		assertThat(comOrdem.turnOrder()).containsExactly("u1", "u2");

		List<String> multidao = new ArrayList<>();
		for (int i = 0; i < TableState.MAX_TURN_ORDER + 1; i++) {
			multidao.add("u" + i);
		}
		assertThatThrownBy(() -> state.withTurnOrder(multidao, LATER))
			.isInstanceOfSatisfying(ApiException.class, e -> {
				assertThat(e.status()).isEqualTo(400);
				assertThat(e.code()).isEqualTo("TURN_ORDER_TOO_LONG");
			});
	}

	@Test
	void quemSaiDaOrdemPerdeAVez() {
		TableState comVez = TableState.empty("mesa_1", "camp_1", NOW)
			.withTurnOrder(List.of("u1", "u2"), NOW)
			.withTurn("u1", NOW);

		TableState semOUm = comVez.withTurnOrder(List.of("u2"), LATER);

		assertThat(semOUm.activeTurnUserId()).isNull();
		assertThat(semOUm.turnStartedAt()).isNull();

		// Ordem nula é ordem limpa, não ordem mantida.
		TableState limpa = comVez.withTurnOrder(null, LATER);
		assertThat(limpa.turnOrder()).isEmpty();
		assertThat(limpa.activeTurnUserId()).isNull();
	}

	@Test
	void vezExigePresencaNaOrdemQuandoAOrdemExiste() {
		TableState comOrdem = TableState.empty("mesa_1", "camp_1", NOW)
			.withTurnOrder(List.of("u1", "u2"), NOW);

		assertThatThrownBy(() -> comOrdem.withTurn("intruso", LATER))
			.isInstanceOfSatisfying(ApiException.class, e -> {
				assertThat(e.status()).isEqualTo(409);
				assertThat(e.code()).isEqualTo("NOT_IN_TURN_ORDER");
			});

		// Sem ordem, qualquer participante pode receber a vez (mesa informal).
		TableState semOrdem = TableState.empty("mesa_1", "camp_1", NOW);
		assertThat(semOrdem.withTurn("u1", LATER).activeTurnUserId()).isEqualTo("u1");
	}

	@Test
	void cenaTemTetoETextoVazioENaoCena() {
		TableState state = TableState.empty("mesa_1", "camp_1", NOW);

		assertThatThrownBy(() -> state.withScene("x".repeat(TableState.MAX_SCENE + 1), LATER))
			.isInstanceOfSatisfying(ApiException.class, e -> {
				assertThat(e.status()).isEqualTo(400);
				assertThat(e.code()).isEqualTo("SCENE_TOO_LONG");
			});

		assertThat(state.withScene("  a taverna arde  ", LATER).scene()).isEqualTo("a taverna arde");
		assertThat(state.withScene("   ", LATER).scene()).isNull();
		assertThat(state.withScene(null, LATER).scene()).isNull();
		assertThat(state.withScene("cena", LATER).updatedAt()).isEqualTo(LATER);
	}
}
