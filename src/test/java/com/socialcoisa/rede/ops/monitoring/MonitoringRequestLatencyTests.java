package com.socialcoisa.rede.ops.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.json.JsonMapper;

/**
 * O p95/p99/p50 de <b>requisição</b> do painel (brief §8.2, "latência p50, p95 e p99" da
 * aplicação) — medido no {@code MonitoringService}, diferente do percentil das <b>tarefas</b> (doc 20
 * §11, por faixas e por nó): aqui é a duração real de cada requisição do Control Plane.
 *
 * <p>Ao medir esta métrica no ciclo 163, o percentil <b>não</b> tinha defeito: para todo n de 1 a
 * 2000 e p em 0,50/0,95/0,99 o nearest-rank é exato em {@code double} (verificado antes de "consertar"
 * o que parecia errado — a suposição de erro de representação não se confirmou e o código do
 * percentil não mudou). O que faltava era o <b>tamanho da amostra</b>: o p95 vinha da janela de 5
 * minutos, sem dizer sobre quantas requisições, e "12 ms de p95" sobre três não é a mesma afirmação
 * que sobre três mil. É o §8.2 que pede a métrica — e uma medida sem denominador não apoia decisão.</p>
 */
class MonitoringRequestLatencyTests {

	@TempDir
	Path dir;

	@Test
	void percentilDaListaCheiaBateComAPosicaoNearestRank() {
		MonitoringService monitoring = service();
		// 2000 durações DISTINTAS: o valor responde em qual índice o percentil caiu. Durações de 1 a
		// 2000 ms; o 1900º menor (índice 1899, 1-indexado 1900) é 1900 ms.
		for (int valor = 1; valor <= 2000; valor++) {
			monitoring.recordRequest("GET", "/x", "/x", 200, valor, null, null, null, null);
		}

		MonitoringService.OverviewView overview = monitoring.overview();

		assertThat(overview.latencySamplesInWindow())
			.as("a janela de 5 minutos tem as 2000 amostras do minuto").isEqualTo(2000);
		assertThat(overview.p95Ms())
			.as("p95 = posição ceil(2000 * 0.95) = 1900 (1-indexado) -> índice 1899 -> valor 1900")
			.isEqualTo(1900);
		assertThat(overview.p99Ms())
			.as("p99 = posição 1980 -> índice 1979 -> valor 1980").isEqualTo(1980);
		assertThat(overview.p50Ms())
			.as("p50 = posição 1000 -> índice 999 -> valor 1000").isEqualTo(1000);
	}

	/**
	 * O p95 é o da própria lista e não tem piso: com duas amostras o nearest-rank devolve o máximo,
	 * e isso é o certo — mas o número vem acompanhado de {@code latencySamplesInWindow}, porque "p95
	 * em 3 s" sobre duas requisições não é a mesma afirmação que sobre duas mil.
	 */
	@Test
	void oTamanhoDaAmostraAcompanhaOP95() {
		MonitoringService monitoring = service();
		monitoring.recordRequest("GET", "/x", "/x", 200, 5, null, null, null, null);
		monitoring.recordRequest("GET", "/x", "/x", 200, 3000, null, null, null, null);

		MonitoringService.OverviewView overview = monitoring.overview();

		assertThat(overview.latencySamplesInWindow()).isEqualTo(2);
		// p95 da posição ceil(2 * 0.95) = 2 -> índice 1 -> 3000 (o maior). Sem piso inventado: o que
		// muda em relação ao painel de 3 mil amostras é o campo que diz o tamanho.
		assertThat(overview.p95Ms()).isEqualTo(3000);
		assertThat(overview.p50Ms()).isEqualTo(5);
	}

	/**
	 * Minuto sem requisição não herda o p95 do vizinho: os pontos da série têm o percentil do
	 * próprio minuto, e o minuto vazio é 0 ("sem dado" no contrato do campo), não o piso de uma
	 * amostra antiga.
	 */
	@Test
	void minutoSemRequisicaoNaoHerdaOP95DoVizinho() {
		// Relógio fixo no meio do minuto: a amostra cai no minuto corrente e o anterior é vazio, sem
		// depender de a rodada atravessar a virada do minuto.
		java.time.Clock fixo = java.time.Clock.fixed(java.time.Instant.parse("2026-11-26T12:00:30Z"),
			java.time.ZoneOffset.UTC);
		MonitoringService monitoring = new MonitoringService(fixo, semPool(),
			new FileEventSink(dir.toString(), 7, JsonMapper.builder().build()), 100_000);
		monitoring.recordRequest("GET", "/x", "/x", 200, 4000, null, null, null, null);

		MonitoringService.OverviewView overview = monitoring.overview();
		// O último ponto da série é o minuto corrente, com a amostra.
		MonitoringService.MinutePoint agora = overview.series().get(overview.series().size() - 1);
		MonitoringService.MinutePoint vazio = overview.series().get(overview.series().size() - 2);

		assertThat(agora.p95Ms()).isEqualTo(4000);
		assertThat(vazio.requests()).as("o minuto anterior é vazio").isZero();
		assertThat(vazio.p95Ms())
			.as("minuto sem requisição não repete a amostra do vizinho").isZero();
	}

	private MonitoringService service() {
		// Relógio fixo, e no meio do minuto: as 2000 amostras caem no MESMO minuto, e a leitura não
		// depende de a rodada atravessar a virada (o mapa de minutos nasce vazio nesta instância).
		return new MonitoringService(java.time.Clock.fixed(java.time.Instant.parse("2026-11-26T12:00:30Z"),
			java.time.ZoneOffset.UTC), semPool(),
			new FileEventSink(dir.toString(), 7, JsonMapper.builder().build()), 100_000);
	}

	/**
	 * O {@code pool()} do serviço pergunta ao {@code ObjectProvider} por um DataSource. Em teste não
	 * há pool: o provedor vazio é o mesmo que o Spring entrega quando o bean não existe, e é o que
	 * faz o painel responder {@code pool: null} em vez de estourar.
	 */
	private static org.springframework.beans.factory.ObjectProvider<javax.sql.DataSource> semPool() {
		return new org.springframework.beans.factory.ObjectProvider<>() {
			@Override
			public javax.sql.DataSource getObject() {
				throw new java.util.NoSuchElementException("sem pool no teste");
			}

			@Override
			public javax.sql.DataSource getObject(Object... args) {
				throw new java.util.NoSuchElementException("sem pool no teste");
			}

			@Override
			public javax.sql.DataSource getIfAvailable() {
				return null;
			}
		};
	}
}
