package com.socialcoisa.rede.network.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.socialcoisa.rede.network.application.port.out.NodeUptimeRepository;
import com.socialcoisa.rede.network.domain.NetworkNode;
import com.socialcoisa.rede.network.domain.NodeAvailability;
import com.socialcoisa.rede.network.domain.NodeStatus;

/**
 * O contador de disponibilidade (V369): o que o heartbeat soma ao dia e o que a leitura devolve.
 *
 * <p>A regra que importa é a do trecho: entre dois contatos, o nó está comprovadamente no ar só até a
 * janela de "vivo" vencer. Depois disso o trecho continua observado — houve um contato no fim —, mas
 * é tempo fora. E o mesmo contato publicado de novo não pode somar o mesmo intervalo duas vezes.</p>
 */
class NodeUptimeServiceTests {

	private static final Instant AGORA = Instant.parse("2026-11-19T12:00:00Z");
	private static final LocalDate HOJE = LocalDate.of(2026, 11, 19);

	private final NodeUptimeRepository repo = mock(NodeUptimeRepository.class);

	private NodeUptimeService uptime(long staleSeconds, long retentionDays) {
		return new NodeUptimeService(repo, Clock.fixed(AGORA, ZoneOffset.UTC), staleSeconds, (int) retentionDays);
	}

	private static NetworkNode no(String id) {
		return new NetworkNode(id, "No", "dono", NodeStatus.ACTIVE, java.util.Set.of(), null, null, null, AGORA, 4, 8192,
			102400, 100, null, null, null, null, (Long) null, 0, null, null);
	}

	@Test
	void o_trecho_no_ar_para_na_janela_de_vivo_e_o_resto_e_tempo_fora() {
		NodeUptimeService uptime = uptime(180, 365);
		Instant de = AGORA.minusSeconds(600);
		// Trecho de 10 min com janela de 3 min: 3 min no ar, 7 min fora.
		assertThat(uptime.presentBetween(de, AGORA)).isEqualTo(180);
		// Trecho mais curto que a janela é todo no ar.
		assertThat(uptime.presentBetween(de, de.plusSeconds(60))).isEqualTo(60);
		// O limite exato da janela ainda é "no ar"; um segundo a mais já é fora.
		assertThat(uptime.presentBetween(de, de.plusSeconds(180))).isEqualTo(180);
		assertThat(uptime.presentBetween(de, de.plusSeconds(181))).isEqualTo(180);
	}

	@Test
	void o_heartbeat_soma_ao_dia_o_trecho_desde_o_contato_anterior() {
		NodeUptimeService uptime = uptime(180, 365);
		when(repo.lastContact(anyString(), any())).thenReturn(null);
		when(repo.add(anyString(), any(), anyLong(), anyLong(), any(), any())).thenReturn(true);

		boolean contou = uptime.record(no("nd_1"), AGORA.minusSeconds(300), AGORA);

		assertThat(contou).isTrue();
		verify(repo).add(eq("nd_1"), eq(HOJE), eq(180L), eq(300L), eq(AGORA.minusSeconds(300)), eq(AGORA));
	}

	@Test
	void um_contato_atrasado_soma_porque_e_trecho_que_ainda_nao_foi_contado() {
		NodeUptimeService uptime = uptime(180, 365);
		// O dia contou até AGORA; este trecho termina antes, numa janela que ninguém contou.
		when(repo.lastContact("nd_1", HOJE)).thenReturn(AGORA);
		when(repo.add(anyString(), any(), anyLong(), anyLong(), any(), any())).thenReturn(true);

		boolean contou = uptime.record(no("nd_1"), AGORA.minusSeconds(600), AGORA.minusSeconds(300));

		assertThat(contou).isTrue();
		verify(repo).add(eq("nd_1"), eq(HOJE), eq(180L), eq(300L), eq(AGORA.minusSeconds(600)),
			eq(AGORA.minusSeconds(300)));
	}

	@Test
	void o_mesmo_contato_publicado_de_novo_nao_soma_o_trecho_duas_vezes() {
		NodeUptimeService uptime = uptime(180, 365);
		// O dia já contou até este mesmo instante: este heartbeat é o mesmo, republicado.
		when(repo.lastContact("nd_1", HOJE)).thenReturn(AGORA);

		boolean contou = uptime.record(no("nd_1"), AGORA.minusSeconds(120), AGORA);

		assertThat(contou).isFalse();
		verify(repo, never()).add(anyString(), any(), anyLong(), anyLong(), any(), any());
	}

	@Test
	void primeiro_contato_nao_inventa_trecho() {
		NodeUptimeService uptime = uptime(180, 365);
		// Sem contato anterior não há intervalo medido: o tempo antes disso não existia para a rede.
		assertThat(uptime.record(no("nd_1"), null, AGORA)).isFalse();
		// Relógio que não avançou também não produz trecho.
		assertThat(uptime.record(no("nd_1"), AGORA, AGORA)).isFalse();
		assertThat(uptime.record(no("nd_1"), AGORA.plusSeconds(1), AGORA)).isFalse();
		verify(repo, never()).add(anyString(), any(), anyLong(), anyLong(), any(), any());
	}

	@Test
	void a_leitura_soma_so_a_janela_e_pondera_os_dias() {
		NodeUptimeService uptime = uptime(180, 365);
		LocalDate tresDias = HOJE.minusDays(3);
		when(repo.days("nd_1", HOJE)).thenReturn(List.of(
			// Fora da janela padrão de 7 dias: não entra.
			new NodeAvailability.Day(HOJE.minusDays(30), 0, 86_400),
			new NodeAvailability.Day(tresDias, 86_400, 86_400),
			new NodeAvailability.Day(HOJE, 86_400 - 7_200, 86_400)));

		NodeAvailability lido = uptime.last(no("nd_1"), 7);

		assertThat(lido.measuredDays()).isEqualTo(2);
		assertThat(lido.observedSeconds()).isEqualTo(172_800);
		assertThat(lido.presentSeconds()).isEqualTo(165_600);
		assertThat(lido.offlineSeconds()).isEqualTo(7_200);
		assertThat(lido.days()).extracting(NodeAvailability.Day::date).containsExactly(tresDias, HOJE);
	}

	@Test
	void a_janela_da_leitura_e_limitada_a_noventa_dias() {
		NodeUptimeService uptime = uptime(180, 365);
		when(repo.days("nd_1", HOJE)).thenReturn(List.of(new NodeAvailability.Day(HOJE.minusDays(100), 1, 1)));
		// Pedir um ano não traz mais do que noventa dias: a rota não pode varrer retenção inteira.
		assertThat(uptime.last(no("nd_1"), 365).measuredDays()).isZero();
		assertThat(uptime.last(no("nd_1"), 90).measuredDays()).isZero();
		// Duas leituras, uma consulta cada: o limite é aplicado na janela, não na consulta.
		verify(repo, org.mockito.Mockito.times(2)).days("nd_1", HOJE);
	}

	@Test
	void a_retencao_apaga_o_que_passou_do_prazo() {
		NodeUptimeService uptime = uptime(180, 30);
		when(repo.purgeBefore(any())).thenReturn(2);

		assertThat(uptime.purge()).isEqualTo(2);

		ArgumentCaptor<LocalDate> limite = ArgumentCaptor.forClass(LocalDate.class);
		verify(repo).purgeBefore(limite.capture());
		// Retenção de 30 dias inclui hoje: o corte é o dia que completa 30 dias atrás.
		assertThat(limite.getValue()).isEqualTo(HOJE.minusDays(29));
	}

	@Test
	void a_janela_de_vivo_e_a_mesma_do_painel() {
		// O construtor aceita o valor do `rede.network.node-stale-seconds`; abaixo de 30 s é elevado,
		// como no serviço de nós — uma janela de zero marcaria todo nó saudável como fora.
		assertThat(uptime(5, 365).presentBetween(AGORA.minusSeconds(60), AGORA))
			.isEqualTo(Duration.ofSeconds(30).getSeconds());
	}
}