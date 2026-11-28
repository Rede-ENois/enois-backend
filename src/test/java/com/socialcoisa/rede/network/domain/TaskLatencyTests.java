package com.socialcoisa.rede.network.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * O histograma de latência por nó (brief §8.2, p50/p95/p99).
 *
 * <p>O que aqui se prende é a <b>aritmética do percentil</b>, que é onde um erro passa despercebido:
 * um p99 calculado como se fosse p90 ainda "parece" um número grande e plausível. Cada caso usa uma
 * distribuição cuja resposta correta é conhecida de antemão.</p>
 */
class TaskLatencyTests {

	@Test
	void semAmostraNaoExistePercentil() {
		TaskLatency vazia = TaskLatency.empty();
		assertThat(vazia.total()).isZero();
		// Zero seria "toda tarefa concluiu em 0 ms", que é medida de execução, não ausência dela.
		assertThat(vazia.p50Ms()).isNull();
		assertThat(vazia.p95Ms()).isNull();
		assertThat(vazia.p99Ms()).isNull();
	}

	/**
	 * O percentil de TENTATIVAS é uma contagem, não uma duração: não passa por {@code bucketOf} e
	 * não interpola.
	 *
	 * <p>Este é o caso que a versão anterior errava: 45 tarefas concluídas de primeira e 5 que
	 * precisaram de 3 retentativas. Com a duração como chave, a chave 3 virava a faixa 4, e o p50
	 * respondia 4 — "quatro tentativas" para quem fez quatro. E chave 0 e chave 1 caíam na mesma
	 * faixa (1), achatando o caso mais comum no segundo mais comum.</p>
	 */
	@Test
	void percentilDeTentativasEContagemDireta() {
		TaskLatency rede = new TaskLatency(Map.of(64, 50), Map.of(0, 45, 3, 5));

		// As 50 posições são contíguas: 1..45 são "0 perdidas" e 46..50 são "3 perdidas".
		assertThat(rede.retriesP50()).as("posição 25: concluiu de primeira").isEqualTo(0);
		assertThat(rede.retriesP95()).as("posição 48: as de 3 entraram").isEqualTo(3);
		assertThat(rede.retries()).as("só quem precisou entra na contagem").isEqualTo(50);

		// Sem as de primeira, todo o percentil é a contagem medida — e nunca a faixa de tempo.
		TaskLatency soRetentativas = new TaskLatency(Map.of(), Map.of(3, 5));
		assertThat(soRetentativas.retriesP50()).isEqualTo(3);
		assertThat(soRetentativas.retriesP99()).isEqualTo(3);

		// Contagem discreta não interpola: nunca "1,5 tentativas".
		assertThat(new TaskLatency(Map.of(), Map.of(1, 1, 2, 1)).retriesP50()).isEqualTo(1);

		// Sem retentativa medida, nulo — não zero.
		assertThat(new TaskLatency(Map.of(64, 10), Map.of()).retriesP50()).isNull();
	}

	/**
	 * Uma tarefa que concluiu na 3ª tentativa (2 perdidas) responde "2" — o valor medido, e não uma
	 * faixa de tempo.
	 */
	@Test
	void concluirNaTerceiraTentativaRespondeDuasTentativasPerdidas() {
		TaskLatency uma = new TaskLatency(Map.of(), Map.of(2, 1));
		assertThat(uma.retriesP50()).isEqualTo(2);
		assertThat(uma.retriesP95()).isEqualTo(2);
	}

	@Test
	void faixaDobraAteSaturar() {
		assertThat(TaskLatency.bucketOf(0)).isEqualTo(1);
		assertThat(TaskLatency.bucketOf(1)).isEqualTo(1);
		assertThat(TaskLatency.bucketOf(2)).isEqualTo(2);
		assertThat(TaskLatency.bucketOf(3)).isEqualTo(4);
		assertThat(TaskLatency.bucketOf(4)).isEqualTo(4);
		assertThat(TaskLatency.bucketOf(5)).isEqualTo(8);
		assertThat(TaskLatency.bucketOf(1000)).isEqualTo(1024);
		// Acima do teto, tudo cai na última faixa: a tarefa não terminou em tempo útil.
		assertThat(TaskLatency.bucketOf(1 << 20)).isEqualTo(1 << 16);
	}

	/**
	 * 90 tarefas rápidas e 10 lentas: o p95 tem de cair LÁ EM CIMA das lentas. Um p95 que olhasse só
	 * a contagem acumulada sem interpolar devolveria o limite da faixa das rápidas.
	 */
	@Test
	void oPercentilDaCaudaNaoParaNaFaixaDasRapidas() {
		// 90 em <=64 ms (faixa 64) e 10 em <=4096 ms (faixa 4096).
		TaskLatency distribuicao = new TaskLatency(Map.of(64, 90, 4096, 10));

		assertThat(distribuicao.total()).isEqualTo(100);
		// p95 = posição 95: cai nas 10 lentas, a 5ª delas → 64 + (5/10)*(4096-64) = 2080.
		assertThat(distribuicao.p95Ms()).isEqualTo(2080);
		// p99 = posição 99: 64 + (9/10)*(4096-64) = 3692,8 → 3693.
		assertThat(distribuicao.p99Ms()).isEqualTo(3693);
		// p50 = posição 50: dentro das 90 rápidas → 0 + (50/90)*64 = 35,6 → 36.
		assertThat(distribuicao.p50Ms()).isEqualTo(36);
	}

	/**
	 * Todas as amostras na mesma faixa: o piso é 0, porque abaixo dela NÃO HÁ mediana nenhuma — dizer
	 * que o percentil está acima de 512 seria afirmar o que ninguém mediu.
	 */
	@Test
	void amostrasNaMesmaFaixaNaoInventamPiso() {
		TaskLatency distribuicao = new TaskLatency(Map.of(1024, 100));
		assertThat(distribuicao.p50Ms()).isEqualTo(512);
		assertThat(distribuicao.p95Ms()).isEqualTo(973);
		assertThat(distribuicao.p99Ms()).isEqualTo(1014);
	}

	/** Faixa vazia no meio não desloca a posição: só conta quem tem amostra. */
	@Test
	void faixaComZeroNaoContaPosicao() {
		TaskLatency comVazio = new TaskLatency(Map.of(8, 0, 16, 10));
		TaskLatency semVazio = new TaskLatency(Map.of(16, 10));
		assertThat(comVazio.total()).isEqualTo(semVazio.total());
		assertThat(comVazio.p95Ms()).isEqualTo(semVazio.p95Ms());
	}

	/**
	 * Uma única amostra numa faixa sem nada abaixo: o percentil é o teto da faixa (o piso é 0), e não
	 * metade dela — que é o que uma interpolação "de dentro" devolveria, afirmando 128 ms para uma
	 * tarefa que só se sabe estar abaixo de 256 ms.
	 */
	@Test
	void umaAmostraSoRespondeOTetoDaFaixa() {
		TaskLatency uma = new TaskLatency(Map.of(256, 1));
		assertThat(uma.p50Ms()).isEqualTo(256);
		assertThat(uma.p95Ms()).isEqualTo(256);
		assertThat(uma.p99Ms()).isEqualTo(256);
	}
}