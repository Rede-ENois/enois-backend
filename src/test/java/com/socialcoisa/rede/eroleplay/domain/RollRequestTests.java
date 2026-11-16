package com.socialcoisa.rede.eroleplay.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.shared.web.ApiException;

/**
 * O pedido de rolagem do mestre.
 *
 * <p>O que este teste tranca e a unica garantia que o pedido oferece ao jogador: respondido uma vez,
 * respondido esta. Rolar de novo ate dar o numero bom e exatamente o que a mesa nao pode fazer, e
 * a recusa e do dominio, nao da tela.</p>
 */
class RollRequestTests {

	private static final Instant NOW = Instant.parse("2026-11-14T10:00:00Z");
	private static final Instant LATER = Instant.parse("2026-11-14T10:05:00Z");

	private static RollRequest pending() {
		return RollRequest.open("req_1", "camp_1", "master_1", "player_1", DiceExpression.parse("1d20"),
			"percepcao", SheetVisibility.GAME_MASTER, "ouvir a porta", NOW);
	}

	@Test
	void nascePendenteComAExpressaoPedida() {
		RollRequest request = pending();

		assertThat(request.status()).isEqualTo(RollRequestStatus.PENDING);
		assertThat(request.expression()).isEqualTo("1d20");
		assertThat(request.label()).isEqualTo("percepcao");
		assertThat(request.reason()).isEqualTo("ouvir a porta");
		assertThat(request.maxVisibility()).isEqualTo(SheetVisibility.GAME_MASTER);
		assertThat(request.answeredRollId()).isNull();
		assertThat(request.answeredAt()).isNull();
	}

	@Test
	void responderMarcaRespondidoComARolagem() {
		RollRequest answered = pending().answer("roll_1", LATER);

		assertThat(answered.status()).isEqualTo(RollRequestStatus.ANSWERED);
		assertThat(answered.answeredRollId()).isEqualTo("roll_1");
		assertThat(answered.answeredAt()).isEqualTo(LATER);
		assertThat(answered.createdAt()).isEqualTo(NOW);
	}

	@Test
	void aSegundaRespostaERecusada() {
		RollRequest answered = pending().answer("roll_1", LATER);

		assertThatThrownBy(() -> answered.answer("roll_2", LATER))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("code", "ROLL_REQUEST_ALREADY_ANSWERED")
			.satisfies(error -> assertThat(((ApiException) error).status()).isEqualTo(409));
	}

	@Test
	void responderNaoAlteraOPedidoOriginal() {
		RollRequest original = pending();
		original.answer("roll_1", LATER);

		// O pedido e imutavel: responder devolve uma copia respondida, e a referencia antiga continua
		// pendente — quem tem a antiga na mao nao "ganha" uma resposta.
		assertThat(original.status()).isEqualTo(RollRequestStatus.PENDING);
		assertThat(original.answeredRollId()).isNull();
	}

	@Test
	void reconstituiOEstadoGravado() {
		RollRequest restored = RollRequest.restore("req_1", "camp_1", "master_1", "player_1", "2d6", "dano",
			SheetVisibility.CAMPAIGN, "acertou", RollRequestStatus.ANSWERED, "roll_9", NOW, LATER);

		assertThat(restored.status()).isEqualTo(RollRequestStatus.ANSWERED);
		assertThat(restored.answeredRollId()).isEqualTo("roll_9");
		assertThat(restored.maxVisibility()).isEqualTo(SheetVisibility.CAMPAIGN);
		assertThat(restored.answeredAt()).isEqualTo(LATER);
	}
}
