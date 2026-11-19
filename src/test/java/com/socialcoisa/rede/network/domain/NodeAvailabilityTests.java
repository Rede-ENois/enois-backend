package com.socialcoisa.rede.network.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

/**
 * A disponibilidade histórica (V369, brief §7).
 *
 * <p>O que se prova é a conta que a tela mostra: a proporção do período é uma soma sobre uma soma,
 * ponderada pelos segundos observados — não a média das proporções dos dias. Um dia com uma hora de
 * medida e um dia inteiro não pesam igual, e o número não pode fingir que pesam.</p>
 */
class NodeAvailabilityTests {

	private static final LocalDate DIA = LocalDate.of(2026, 11, 19);

	@Test
	void a_proporcao_do_periodo_pondera_pelos_segundos_observados_e_nao_pela_media_dos_dias() {
		NodeAvailability doisDias = NodeAvailability.of(List.of(
			// Dia quase inteiro no ar: 1 h fora em 86 400 s.
			new NodeAvailability.Day(DIA, 86_400 - 3_600, 86_400),
			// Dia com 10 min de medida, todos fora.
			new NodeAvailability.Day(DIA.plusDays(1), 0, 600)));

		double ponderada = 100.0 * (82_800) / 87_000;
		// `OptionalDouble` do Java, sem o AssertJ de Optional: o valor é o da conta, não o da lib.
		assertThat(doisDias.percentage()).isNotNull();
		assertThat(doisDias.percentage()).isCloseTo(ponderada, within(1e-9));
		// A média das proporções daria (95,83 + 0) / 2 = 47,9% — um número que nenhum dos dois dias viveu.
		assertThat(doisDias.percentage()).isNotCloseTo(47.9, within(1.0));
		assertThat(doisDias.offlineSeconds()).isEqualTo(4_200);
		assertThat(doisDias.measuredDays()).isEqualTo(2);
	}

	@Test
	void sem_tempo_observado_a_proporcao_e_vazia_e_nao_zero() {
		// Nó recém-criado: nenhum dia medido. "0% no ar" diria que ele esteve fora, que é outra coisa.
		// `null` e não 0: a tela mostra "sem dado", não "0% no ar".
		assertThat(NodeAvailability.empty().percentage()).isNull();
		assertThat(NodeAvailability.empty().measuredDays()).isZero();
		assertThat(NodeAvailability.empty().offlineSeconds()).isZero();
		assertThat(new NodeAvailability.Day(DIA, 0, 0).percentage()).isEmpty();
	}

	@Test
	void dia_com_mais_tempo_no_ar_do_que_observado_e_recusado() {
		// Impossível por construção; engolir em silêncio mostraria 100% de um dado corrompido.
		assertThatThrownBy(() -> new NodeAvailability.Day(DIA, 100, 50))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("2026-11-19");
		assertThatThrownBy(() -> new NodeAvailability.Day(DIA, -1, 50))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void o_dia_conhece_a_propria_proporcao() {
		assertThat(new NodeAvailability.Day(DIA, 50, 200).percentage().getAsDouble()).isCloseTo(25.0, within(1e-9));
	}

	private static Offset<Double> within(double valor) {
		return Offset.offset(valor);
	}
}