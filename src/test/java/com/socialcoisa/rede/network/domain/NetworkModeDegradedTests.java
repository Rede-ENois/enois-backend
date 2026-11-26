package com.socialcoisa.rede.network.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.network.domain.NetworkMode.HealthSignal;

/**
 * O modo {@code DEGRADED} é de decisão humana, e com razão: a automação não sabe o que a rede não
 * mediu. Mas as duas causas que ela <b>mede</b> e que tornam a rede degradada em cima dos rostos —
 * banco principal que não serve para captura e evidência de divergência — ficavam só no documento.
 * O §12 do brief pede transições com critérios e registro de auditoria; aqui é o critério.
 */
class NetworkModeDegradedTests {

	private static final Instant AGORA = Instant.parse("2026-11-25T12:00:00Z");

	@Test
	void sem_evidencia_a_rede_continua_no_modo_medido() {
		assertThat(NetworkMode.NORMAL.degradedBy(HealthSignal.NONE)).isEqualTo(NetworkMode.NORMAL);
		assertThat(NetworkMode.HIGH_LOAD.degradedBy(HealthSignal.NONE)).isEqualTo(NetworkMode.HIGH_LOAD);
	}

	@Test
	void captura_declinada_degrade_a_rede_mesmo_com_dado_conhecido() {
		assertThat(NetworkMode.NORMAL.degradedBy(HealthSignal.CAPTURE_NOT_READY)).isEqualTo(NetworkMode.DEGRADED);
		assertThat(NetworkMode.HIGH_LOAD.degradedBy(HealthSignal.CAPTURE_NOT_READY))
			.isEqualTo(NetworkMode.DEGRADED);
	}

	@Test
	void divergencia_degrade_e_ganha_do_captura_para_que_o_motivo_seja_o_pior() {
		assertThat(NetworkMode.NORMAL.degradedBy(HealthSignal.DATA_DIVERGENCE)).isEqualTo(NetworkMode.DEGRADED);
		assertThat(HealthSignal.of(false, true, true)).isEqualTo(HealthSignal.DATA_DIVERGENCE);
	}

	@Test
	void ausencia_de_medida_nao_vira_evidencia() {
		// "não sei" (ready nulo) e "não é exigido" continuam sem sinal: medir não é adivinhar.
		assertThat(HealthSignal.of(null, false)).isEqualTo(HealthSignal.NONE);
		assertThat(HealthSignal.of(true, false)).isEqualTo(HealthSignal.NONE);
		assertThat(HealthSignal.of(false, false)).isEqualTo(HealthSignal.NONE);
		// Exigida a captura, "não sei" continua não sendo defeito: só a medida negativa é.
		assertThat(HealthSignal.of(null, true)).isEqualTo(HealthSignal.NONE);
		assertThat(HealthSignal.of(false, true)).isEqualTo(HealthSignal.CAPTURE_NOT_READY);
	}

	@Test
	void emergencia_e_recuperacao_nao_sao_rebaixadas_pela_evidencia() {
		// EMERGENCY já é o pior estado (dizer DEGRADED apagaria a razão do incidente) e RECOVERY
		// despacha pela metade de propósito: degradar ali tiraria o metro do meio caminho de volta.
		assertThat(NetworkMode.EMERGENCY.degradedBy(HealthSignal.DATA_DIVERGENCE)).isEqualTo(NetworkMode.EMERGENCY);
		assertThat(NetworkMode.RECOVERY.degradedBy(HealthSignal.CAPTURE_NOT_READY)).isEqualTo(NetworkMode.RECOVERY);
	}

	@Test
	void saida_de_incidente_devolve_o_modo_medido_e_nao_mantem_o_degradado() {
		// DEGRADED é saída explícita do humano (NetworkModeService.declare); a automação não o desfaz.
		// O caminho de volta que importa é o do modo medido: ele tem de continuar automático.
		assertThat(NetworkMode.NORMAL.automatic()).isTrue();
		assertThat(NetworkMode.DEGRADED.automatic()).isFalse();
		assertThat(NetworkMode.NORMAL.degradedBy(HealthSignal.NONE).automatic()).isTrue();
	}

	@Test
	void a_janela_de_vida_e_a_mesma_do_scheduler_e_do_painel() {
		// Se o modo usasse outra janela, a rede estaria viva na tela e morta no despacho no mesmo
		// instante. O teste fixa os dois lados da fronteira.
		assertThat(NodeHealth.ALIVE_WINDOW).isEqualTo(Duration.ofSeconds(180));
	}
}