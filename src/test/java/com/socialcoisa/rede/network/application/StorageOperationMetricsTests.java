package com.socialcoisa.rede.network.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import com.socialcoisa.rede.network.application.StorageOperationMetrics.Linha;
import com.socialcoisa.rede.network.application.StorageOperationMetrics.Resultado;

/**
 * Operações por segundo e latência do painel (§17, V366).
 *
 * <p><b>O que estes testes fixam.</b> Antes não havia contador nenhum, então "Ops/s" e "Latência" não
 * podiam existir no painel. A implementação tem três jeitos fáceis de errar que deixariam o número
 * errado <i>e verde</i>:</p>
 *
 * <ul>
 * <li><b>média das médias</b>: guardar a média por minuto e tirar a média delas pesa um minuto parado
 * igual a um minuto de pico. O teste do salto (10 op a 10 ms e 1 op a 1000 ms → 100 ms, não 505) é a
 * asserção que discrimina;</li>
 * <li><b>zero em vez de "sem dado"</b>: sem operação, a taxa é 0 e o tempo coberto é 0 — não "0 ops/s
 * porque está parado há 15 minutos" calculado sobre uma janela que nunca foi medida;</li>
 * <li><b>500 e 400 no mesmo balde</b>: separá-los é o que distingue "clientes quebrados" de "Storage
 * falhando" sem abrir log.</li>
 * </ul>
 *
 * <p>A conta é exercitada pelo método real que o serviço usa ({@link StorageOperationMetrics#aggregate});
 * a escrita é exercitada com um {@code JdbcTemplate} de mentira que captura os parâmetros do UPSERT — se
 * o verbo deixar de ser normalizado, ou o resultado parar de ser gravado, o teste reprova.</p>
 */
class StorageOperationMetricsTests {

	private static final Instant AGORA = Instant.parse("2026-11-19T12:00:10Z");
	private static final Instant DE = AGORA.minus(Duration.ofMinutes(15));

	private final Clock relogio = Clock.fixed(AGORA, ZoneOffset.UTC);
	private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

	private StorageOperationMetrics servico() {
		return new StorageOperationMetrics(jdbc, relogio, 30);
	}

	@Test
	void a_media_da_janela_e_ponderada_por_operacao_e_nao_media_das_medias() {
		// Minuto A: 10 operações de 10 ms. Minuto B: 1 operação de 1000 ms.
		// Média das médias = (10 + 1000) / 2 = 505 ms; a verdade é (100 + 1000) / 11 = 100 ms.
		StorageOperationMetrics.Report relatorio = StorageOperationMetrics.aggregate(DE, AGORA, List.of(
			new Linha("GET", "OK", 10, 100, 10),
			new Linha("GET", "OK", 1, 1000, 1000)));

		assertThat(relatorio.operations()).isEqualTo(11);
		assertThat(relatorio.averageMs()).isEqualTo(100.0);
		// O pior caso continua visível: média baixa com máximo alto é o sintoma de "o cliente reclama".
		assertThat(relatorio.maxMs()).isEqualTo(1000);
	}

	@Test
	void sem_operacao_a_taxa_e_zero_e_o_tempo_coberto_tambem() {
		StorageOperationMetrics.Report relatorio = servico().report(Duration.ofMinutes(15));

		assertThat(relatorio.operations()).isZero();
		assertThat(relatorio.opsPerSecond()).isZero();
		// Zero segundos cobertos: não inventamos uma janela de 900 s "parada" que ninguém mediu.
		assertThat(relatorio.seconds()).isZero();
		assertThat(relatorio.byOperation()).isEmpty();
	}

	@Test
	void quatrocentos_e_cincocentos_ficam_em_baldes_diferentes() {
		StorageOperationMetrics.Report relatorio = StorageOperationMetrics.aggregate(DE, AGORA, List.of(
			new Linha("PUT", "OK", 1, 5, 5),
			new Linha("PUT", "CLIENT", 2, 10, 5),
			new Linha("PUT", "SERVER", 1, 30, 30)));

		assertThat(relatorio.byOutcome()).containsEntry("OK", 1L).containsEntry("CLIENT", 2L)
			.containsEntry("SERVER", 1L);
		assertThat(relatorio.byOperation()).containsEntry("PUT", 4L);
	}

	@Test
	void a_taxa_usa_o_tempo_realmente_coberto_pela_janela() {
		// Uma operação em 900 s → 1/900 = 0,00111... arredondado a 3 casas = 0,001 ops/s. Um contador
		// que dividisse pelo número de minutos com linha (1) daria 1 op/s — mil vezes mais.
		StorageOperationMetrics.Report relatorio = StorageOperationMetrics.aggregate(DE, AGORA,
			List.of(new Linha("GET", "OK", 1, 1, 1)));
		assertThat(relatorio.seconds()).isEqualTo(900);
		assertThat(relatorio.opsPerSecond()).isEqualTo(0.001);
	}

	@Test
	void janela_invalida_cai_no_padrao_de_quinze_minutos() {
		StorageOperationMetrics.Report relatorio = servico().report(Duration.ZERO);
		assertThat(Duration.between(relatorio.from(), relatorio.to())).isEqualTo(Duration.ofMinutes(15));
	}

	@Test
	void a_operacao_desconhecida_entra_como_other_e_nunca_como_verbete_inventado() {
		servico().record("FOO", Resultado.OK, 7);

		ArgumentCaptor<Object> parametros = ArgumentCaptor.forClass(Object.class);
		verify(jdbc).update(anyString(), parametros.capture(), parametros.capture(), parametros.capture(),
			parametros.capture(), parametros.capture(), parametros.capture(), parametros.capture());
		assertThat(parametros.getAllValues()).containsSubsequence("OTHER", "OK", 7L, 7L, 7L, 7L);
	}

	@Test
	void o_upsert_soma_no_minuto_certo_e_grava_o_pior_caso() {
		servico().record("PUT", Resultado.SERVER, 42);

		ArgumentCaptor<Object> parametros = ArgumentCaptor.forClass(Object.class);
		verify(jdbc).update(anyString(), parametros.capture(), parametros.capture(), parametros.capture(),
			parametros.capture(), parametros.capture(), parametros.capture(), parametros.capture());
		assertThat(((Timestamp) parametros.getAllValues().get(0)).toInstant())
			.isEqualTo(Instant.parse("2026-11-19T12:00:00Z"));
		assertThat(parametros.getAllValues()).containsSubsequence("PUT", "SERVER", 42L, 42L, 42L, 42L);
	}

	@Test
	void a_retencao_apaga_antes_do_limite_e_devolve_quantas_sairam() {
		when(jdbc.update(anyString(), any(Object.class))).thenReturn(3);

		int removidas = servico().purge();

		assertThat(removidas).isEqualTo(3);
		ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
		ArgumentCaptor<Object> limite = ArgumentCaptor.forClass(Object.class);
		verify(jdbc).update(sql.capture(), limite.capture());
		assertThat(sql.getValue()).isEqualTo("delete from network_storage_operation_metrics where bucket_at < ?");
		assertThat(((Timestamp) limite.getValue()).toInstant())
			.isEqualTo(Instant.parse("2026-10-20T12:00:10Z"));
	}

	@Test
	void falha_ao_registrar_nao_derruba_a_operacao() {
		doThrow(new IllegalStateException("banco fora")).when(jdbc).update(anyString(), any(Object.class));

		// Não propaga: a métrica é observação; perder uma amostra não pode virar erro de upload.
		servico().record("PUT", Resultado.OK, 5);
	}
}