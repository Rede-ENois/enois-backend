package com.socialcoisa.rede.shared.html;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Custo da limpeza de HTML/CSS.
 *
 * A limpeza roda numa thread de requisicao. Um regex que resolve o mesmo trabalho em tempo
 * quadratico nao aparece em teste de correcao — o resultado sai certo, so demora. O que acontece
 * entao e o pool de conexoes esgotar enquanto a thread gira, e a aplicacao inteira cair junto.
 * Estes casos travam o custo: entrada escolhida para ser hostil precisa terminar em tempo constante.
 */
class SafeHtmlCostTests {

	private static final Duration LIMITE = Duration.ofSeconds(2);
	private static final int TAMANHO = 60_000;

	/** O custo e o que importa aqui; recusar por tamanho tambem e um fim aceitavel. */
	private static void limpar(String raw) {
		try {
			SafeHtml.sanitizeProfileHtml(raw);
		}
		catch (ApiException recusa) {
			// esperado para o que passa do teto de CSS ou do tamanho do perfil
		}
	}

	private static String repetir(String trecho, int vezes) {
		return trecho.repeat(vezes);
	}

	@Test
	void cssSemDelimitadorTerminaRapido() {
		String payload = "<style>" + repetir("a", TAMANHO) + "</style><p>ok</p>";
		assertTimeoutPreemptively(LIMITE, () -> limpar(payload));
	}

	@Test
	void urlSemFechamentoTerminaRapido() {
		String payload = "<style>" + repetir("url(", TAMANHO / 4) + "</style><p>ok</p>";
		assertTimeoutPreemptively(LIMITE, () -> limpar(payload));
	}

	@Test
	void urlComFechamentoNoFimTerminaRapido() {
		String payload = "<style>" + repetir("url(", TAMANHO / 4) + ")</style><p>ok</p>";
		assertTimeoutPreemptively(LIMITE, () -> limpar(payload));
	}

	@Test
	void blocoDeEstiloSemFechamentoTerminaRapido() {
		String payload = repetir("<style>", TAMANHO / 7) + "<p>ok</p>";
		assertTimeoutPreemptively(LIMITE, () -> limpar(payload));
	}

	@Test
	void comentarioSemFechamentoTerminaRapido() {
		String payload = "<style>" + repetir("/*", TAMANHO / 2) + "</style><p>ok</p>";
		assertTimeoutPreemptively(LIMITE, () -> limpar(payload));
	}

	@Test
	void declaracaoComTokenPerigosoNoFimTerminaRapido() {
		String payload = "<style>" + repetir("a", TAMANHO) + "expression(x)</style><p>ok</p>";
		assertTimeoutPreemptively(LIMITE, () -> limpar(payload));
	}

	@Test
	void tokensPerigososRepetidosTerminamRapido() {
		String payload = "<style>" + repetir("behavior:url(x);", TAMANHO / 16) + "</style><p>ok</p>";
		assertTimeoutPreemptively(LIMITE, () -> limpar(payload));
	}

	/**
	 * Tokens perigosos colados, sem `;`/`{`/`}` entre eles: a declaracao que contem o primeiro token
	 * vai ate o fim da folha, e os outros ~4.600 casamentos caem dentro do trecho ja apagado. Se o
	 * laco voltar a varrer o resto da folha para cada um deles, o custo vira quadratico de novo —
	 * e o teto aqui e apertado de proposito, porque so o caminho correto termina nessa faixa.
	 */
	@Test
	void tokensPerigososSemDelimitadorTerminamRapido() {
		String payload = "<style>a:" + repetir("expression(x)", 4_600) + "</style><p>ok</p>";
		assertTimeoutPreemptively(Duration.ofMillis(300), () -> limpar(payload));
	}

	@Test
	void urlComEspacoNoArgumentoSemAspasEhRecusado() {
		String out = SafeHtml.requireProfileHtml("<style>.a{background:url(foo bar)}</style><p>ok</p>");
		assertThat(out).doesNotContain("url(");
		assertThat(out).doesNotContain("foo bar");
	}

	@Test
	void markupSemFechamentoDeTagTerminaRapido() {
		String payload = "<p>ok</p>" + repetir("<a ", TAMANHO / 3);
		assertTimeoutPreemptively(LIMITE, () -> limpar(payload));
	}

	@Test
	void limpezaContinuaValendoParaCss() {
		String out = SafeHtml.requireProfileHtml(
			"<style>.a{background:expression(alert(1));} .b{background:url(https://malicioso.test/x.png)}"
				+ " .c{border-image:image-set('a.png')} .d{scroll-behavior:smooth}"
				+ " .e{background:url(data:image/png;base64,AAAA)}</style><p>ok</p>");
		assertThat(out).doesNotContain("expression(");
		assertThat(out).doesNotContain("image-set(");
		assertThat(out).doesNotContain("malicioso.test");
		assertThat(out).contains("scroll-behavior:smooth");
		assertThat(out).contains("data:image/png");
	}

	@Test
	void blocoDeEstiloContinuaSendoExtraido() {
		String out = SafeHtml.requireProfileHtml("<style>.a{color:red}</style><p>ok</p>");
		assertThat(out).startsWith("<style>");
		assertThat(out).contains(".a{color:red}");
		assertThat(out).contains("<p>ok</p>");
	}

	@Test
	void cssAcimaDoTetoERecusadoComMensagem() {
		String payload = "<style>" + repetir("a", 64_010) + "</style><p>ok</p>";
		try {
			SafeHtml.sanitizeProfileHtml(payload);
			assertThat(false).as("deveria recusar CSS acima do teto").isTrue();
		}
		catch (ApiException recusa) {
			assertThat(recusa.getMessage()).contains("CSS");
		}
	}

	/**
	 * Uma instancia do app atende muitas requisicoes ao mesmo tempo. A limpeza nao guarda estado
	 * entre chamadas: cada uma cria o proprio matcher e trabalha sobre valores locais. Este teste
	 * executa chamadas concorrentes com entradas diferentes e confere que nenhuma resposta carrega
	 * resto da outra.
	 */
	@Test
	void chamadasConcorrentesNaoSeMisturam() throws Exception {
		List<Callable<String>> tarefas = List.of(
			() -> SafeHtml.requireProfileHtml("<style>.um{color:red}</style><p>um</p>"),
			() -> SafeHtml.requireProfileHtml("<style>.dois{color:blue}</style><p>dois</p>"),
			() -> SafeHtml.requireProfileHtml("<style>.tres{color:green}</style><p>tres</p>"),
			() -> SafeHtml.requireProfileHtml("<style>.quatro{color:black}</style><p>quatro</p>"));
		ExecutorService pool = Executors.newFixedThreadPool(8);
		try {
			List<Future<String>> futures = new java.util.ArrayList<>();
			for (int rodada = 0; rodada < 50; rodada++) {
				for (Callable<String> tarefa : tarefas) {
					futures.add(pool.submit(tarefa));
				}
			}
			for (int i = 0; i < futures.size(); i++) {
				String out = futures.get(i).get();
				String esperado = switch (i % 4) {
					case 0 -> "um";
					case 1 -> "dois";
					case 2 -> "tres";
					default -> "quatro";
				};
				assertThat(out).contains("<p>" + esperado + "</p>");
				for (String outro : List.of("um", "dois", "tres", "quatro")) {
					if (!outro.equals(esperado)) {
						assertThat(out).doesNotContain("<p>" + outro + "</p>");
					}
				}
			}
		}
		finally {
			pool.shutdownNow();
		}
	}
}
