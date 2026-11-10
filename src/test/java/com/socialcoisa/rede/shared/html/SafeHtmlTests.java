package com.socialcoisa.rede.shared.html;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Limpeza do HTML das paginas. Estes testes existem porque o sanitizador e a unica barreira que
 * segue valendo fora do navegador (app, e-mail, crawler) — o iframe sandboxed protege o front,
 * mas nao protege quem consumir o conteudo por outro caminho.
 */
class SafeHtmlTests {

	@Test
	void cssAnimationsSurviveTheSanitizer() {
		// O recurso "sobre/pagina com HTML+CSS" perde o sentido sem animacao: keyframes, animation,
		// transition e pseudo-elementos sao o que as pessoas colam (ex.: a.singlediv.com). Nada disso
		// e perigoso no sandbox (sem script), entao a garantia fica travada aqui.
		String clean = SafeHtml.sanitizeProfileHtml("""
			<style>
			  @keyframes pulse {
			    0% { transform: scale(1); opacity: 1; }
			    100% { transform: scale(1.08); opacity: .85; }
			  }
			  .card {
			    background: linear-gradient(45deg, #7c38ee, #ff0080);
			    animation: pulse 2s infinite alternate;
			    transition: transform .3s ease;
			    scroll-behavior: smooth;
			  }
			  .card:hover { transform: rotate(2deg); }
			  .card::after { content: "*"; }
			</style>
			<div class="card" style="animation: pulse 3s infinite;">Ola</div>
			""");

		assertThat(clean).contains("@keyframes pulse");
		assertThat(clean).contains("animation: pulse 2s infinite alternate");
		assertThat(clean).contains("transition: transform .3s ease");
		assertThat(clean).contains("scroll-behavior: smooth");
		assertThat(clean).contains(".card:hover");
		assertThat(clean).contains(".card::after");
		assertThat(clean).contains("style=\"animation: pulse 3s infinite;\"");
	}

	@Test
	void dangerousCssStillDies() {
		String clean = SafeHtml.sanitizeProfileHtml("""
			<style>
			  .a { behavior: url(script.htc); width: expression(alert(1)); background: url(javascript:alert(2)); }
			  @import url("https://evil.test/track.css");
			</style>
			<div class="a">x</div>
			""");

		assertThat(clean).doesNotContain("behavior").doesNotContain("expression").doesNotContain("javascript");
		assertThat(clean).doesNotContain("@import").doesNotContain("evil.test");
	}

	@Test
	void removesScriptTagAndItsContent() {
		String clean = SafeHtml.sanitizePageHtml("<p>antes</p><script>alert('xss')</script><p>depois</p>");

		assertThat(clean).doesNotContain("<script").doesNotContain("alert");
		assertThat(clean).contains("antes").contains("depois");
	}

	@Test
	void removesEventHandlersEvenOnAllowedTags() {
		String clean = SafeHtml.sanitizePageHtml("<img src=\"https://exemplo.com/foto.png\" onerror=\"alert(1)\">");

		assertThat(clean).doesNotContain("onerror").doesNotContain("alert");
		assertThat(clean).contains("https://exemplo.com/foto.png");
	}

	@Test
	void removesJavascriptUrls() {
		String clean = SafeHtml.sanitizePageHtml("<a href=\"javascript:alert(1)\">clique</a>");

		assertThat(clean).doesNotContain("javascript:");
		assertThat(clean).contains("clique");
	}

	@Test
	void keepsFormattingTablesListsAndLinks() {
		String clean = SafeHtml.sanitizePageHtml("""
			<h2>Titulo</h2><p><strong>negrito</strong> e <em>italico</em></p>
			<ul><li>um</li><li>dois</li></ul>
			<table><tr><th>a</th><th>b</th></tr><tr><td>1</td><td>2</td></tr></table>
			<a href="https://exemplo.com">link</a>
			""");

		assertThat(clean).contains("<h2>Titulo</h2>");
		assertThat(clean).contains("<strong>negrito</strong>");
		assertThat(clean).contains("<li>um</li>");
		assertThat(clean).contains("<th>a</th>");
		assertThat(clean).contains("href=\"https://exemplo.com\"");
	}

	/**
	 * Midia embutida: imagem, video e audio rodam no navegador sem script nenhum (quem toca e o
	 * proprio navegador, com controles nativos), entao a promessa de "nenhum script executa"
	 * continua valendo com elas.
	 */
	@Test
	void keepsNativeMediaSoItPlaysInTheBrowser() {
		String clean = SafeHtml.sanitizePageHtml("""
			<figure><img src="https://exemplo.com/foto.png" alt="foto"><figcaption>A foto</figcaption></figure>
			<video controls poster="https://exemplo.com/capa.jpg" src="https://exemplo.com/clipe.mp4">texto</video>
			<audio controls src="https://exemplo.com/musica.mp3"></audio>
			<picture><source src="https://exemplo.com/foto.webp" type="image/webp"><img src="https://exemplo.com/foto.png" alt=""></picture>
			<video controls><source src="https://exemplo.com/b.webm" type="video/webm"><track src="https://exemplo.com/leg.vtt" kind="captions"></video>
			""");

		assertThat(clean).contains("<figcaption>A foto</figcaption>");
		assertThat(clean).contains("controls");
		assertThat(clean).contains("poster=\"https://exemplo.com/capa.jpg\"");
		assertThat(clean).contains("<audio");
		assertThat(clean).contains("<source src=\"https://exemplo.com/b.webm\"");
		assertThat(clean).contains("<track src=\"https://exemplo.com/leg.vtt\"");
		assertThat(clean).doesNotContain("autoplay");
	}

	/**
	 * Autoplay: o atributo sobrevive, e o que o navegador exige para ele acontecer de verdade vem
	 * junto — video com som nunca toca sozinho (politica de autoplay dos navegadores), entao ganha
	 * muted + playsinline; audio sem controles seria invisivel caso o autoplay fosse recusado.
	 * Autoplay nao pedido nao e inventado (o teste de midia nativa acima continua provando isso).
	 */
	@Test
	void autoplaySurvivesAndGetsWhatBrowsersRequireToActuallyPlay() {
		String clean = SafeHtml.sanitizePageHtml("""
			<video autoplay src="https://exemplo.com/clipe.mp4"></video>
			<video autoplay muted playsinline loop src="https://exemplo.com/fundo.mp4"></video>
			<audio autoplay src="https://exemplo.com/musica.mp3"></audio>
			""");

		assertThat(clean).contains("autoplay");
		// Video sem muted sai completo (politica de autoplay exige muted + playsinline para tocar);
		// quem ja escreveu o par nao e reescrito. jsoup serializa atributos vazios como `attr=""`
		// e booleanos conhecidos sem valor — para o navegador as duas formas valem "presente".
		assertThat(clean).contains(
			"<video autoplay=\"\" src=\"https://exemplo.com/clipe.mp4\" muted playsinline=\"playsinline\"></video>");
		assertThat(clean).contains("loop=\"\"");
		// Audio: controles entram como caminho de fallback caso o navegador recuse o autoplay.
		assertThat(clean).contains("<audio autoplay=\"\" src=\"https://exemplo.com/musica.mp3\" controls=\"controls\"></audio>");
	}

	@Test
	void mediaOnlyByHttpsAndDocumentsStayAsLinks() {
		// http nao carregaria numa pagina https (conteudo misto): o atributo sai.
		String httpMedia = SafeHtml.sanitizePageHtml("<video controls src=\"http://exemplo.com/clipe.mp4\"></video>");
		assertThat(httpMedia).doesNotContain("http://");

		// Quadro dentro de quadro exigiria liberar script (player de terceiros) e o visualizador de
		// PDF do Chrome tambem nao roda no sandbox: documento vai como link, que abre no navegador.
		String embeds = SafeHtml.sanitizePageHtml(
			"<iframe src=\"https://exemplo.com/doc.pdf\"></iframe><embed src=\"https://exemplo.com/doc.pdf\">"
				+ "<object data=\"https://exemplo.com/doc.pdf\"></object><a href=\"https://exemplo.com/doc.pdf\">Abrir</a>");

		assertThat(embeds).doesNotContain("<iframe").doesNotContain("<embed").doesNotContain("<object");
		assertThat(embeds).contains("href=\"https://exemplo.com/doc.pdf\"");
	}

	@Test
	void keepsSafeInlineStyleAndDropsEveryRemoteCssUrl() {
		String clean = SafeHtml.sanitizePageHtml(
			"<div style=\"color:#123456;background:url(https://exemplo.com/bg.png);"
				+ "border-image:url(http://rastreador.test/pixel);cursor:url(data:image/png;base64,AAA)\">conteudo</div>");

		// A cor fica (nao faz requisicao nenhuma); qualquer url() remota sai — inclusive https, que
		// era o rastro invisivel: nao aparece na tela e ainda avisa o dono do endereco que a pagina
		// foi aberta. Imagem remota continua valendo por `<img>`, que aparece no markup.
		assertThat(clean).contains("color").contains("#123456");
		assertThat(clean).contains("data:image/png;base64,AAA");
		assertThat(clean).doesNotContain("exemplo.com/bg.png").doesNotContain("rastreador.test");
	}

	@Test
	void styleBlockIsPreservedButSanitized() {
		String clean = SafeHtml.sanitizePageHtml("""
			<html><head><style>
			@import url("https://externo.test/estilo.css");
			.card { color: red; background: url("http://externo.test/a.png"); padding: 8px }
			.x { width: expression(alert(1)) }
			</style></head><body><div class="card">oi</div></body></html>
			""");

		assertThat(clean).contains("<style>");
		assertThat(clean).contains(".card");
		assertThat(clean).doesNotContain("@import").doesNotContain("expression").doesNotContain("externo.test");
		assertThat(clean).contains("<div class=\"card\">oi</div>");
	}

	@Test
	void cannotBreakOutOfStyleBlock() {
		String clean = SafeHtml.sanitizePageHtml("<style>body{color:red}</style><!--</style><script>alert(1)</script>--><p>ok</p>");

		assertThat(clean).doesNotContain("<script");
		assertThat(clean).doesNotContain("alert");
		assertThat(clean).contains("ok");
	}

	@Test
	void blankInputBecomesNullAndPageTypeRequiresHtml() {
		assertThat(SafeHtml.sanitizePageHtml("   ")).isNull();
		assertThat(SafeHtml.sanitizePageHtml(null)).isNull();
		// Sobra so markup perigoso: nada util para renderizar, entao a publicacao tem de falhar.
		assertThat(SafeHtml.sanitizePageHtml("<script>alert(1)</script>")).isNull();
		assertThatThrownBy(() -> SafeHtml.requirePageHtml("<script>alert(1)</script>"))
			.isInstanceOf(ApiException.class)
			.hasMessageContaining("HTML");
	}

	@Test
	void rejectsHugePastesWithoutParsing() {
		assertThatThrownBy(() -> SafeHtml.sanitizePageHtml("<p>" + "a".repeat(400_001) + "</p>"))
			.isInstanceOf(ApiException.class);
	}

	@Test
	void acceptsPageNearTheLimitAndRejectsOnlyAboveIt() {
		// O teto e um so para todo mundo: abaixo dele a pagina passa, acima e recusada — sem
		// faixa intermediaria, sem excecao por conta.
		assertThat(SafeHtml.sanitizePageHtml("<p>" + "a".repeat(SafeHtml.MAX_PAGE_HTML - 10) + "</p>"))
			.isNotNull();
		assertThatThrownBy(() -> SafeHtml.sanitizePageHtml("<p>" + "a".repeat(SafeHtml.MAX_PAGE_HTML) + "</p>"))
			.isInstanceOf(ApiException.class)
			.hasMessageContaining(String.valueOf(SafeHtml.MAX_PAGE_HTML));
	}

	/**
	 * Embed de provedor conhecido: o quadro nao roda script, entao o player nao pode morar la dentro
	 * — mas a INTENCAO ("aqui vai um video") fica guardada num marcador, e quem monta o player e o
	 * app. Host que nao e provedor conhecido nao vira marcador: sai fora.
	 */
	@Test
	void keepsProviderEmbedAndDropsUnknownOnes() {
		String clean = SafeHtml.sanitizePageHtml("""
			<iframe width="560" height="315" src="https://www.youtube.com/embed/aqz-KE-bpKQ" frameborder="0"></iframe>
			<iframe src="https://player.vimeo.com/video/76979871"></iframe>
			<iframe src="https://open.spotify.com/embed/track/4cOdK2wGLETKBW3PvgPWqT"></iframe>
			<iframe src="https://malicioso.test/embed/x"></iframe>
			<embed src="http://www.youtube.com/embed/aqz-KE-bpKQ">
			<iframe src="https://evil.test/y"></iframe>
			""");

		assertThat(clean).doesNotContain("<embed").doesNotContain("malicioso.test").doesNotContain("evil.test");
		// Provedores conhecidos ficam (o player roda DENTRO da pagina, na origem de conteudo); http e
		// host desconhecido saem fora.
		assertThat(clean).contains("src=\"https://www.youtube.com/embed/aqz-KE-bpKQ\"");
		assertThat(clean).contains("src=\"https://player.vimeo.com/video/76979871\"");
		assertThat(clean).contains("src=\"https://open.spotify.com/embed/track/4cOdK2wGLETKBW3PvgPWqT\"");
		// O quadro reescrito delega autoplay/tela cheia ao player (o autoplay=1 da URL so toca com
		// essa delegacao); o navegador continua dono da decisao final.
		assertThat(clean).contains("allow=\"autoplay; fullscreen; encrypted-media\"");
		assertThat(clean.split("<iframe", -1).length - 1).isEqualTo(3);
	}

	@Test
	void providerEmbedCannotSmuggleMarkupOrScript() {
		String clean = SafeHtml.sanitizePageHtml("""
			<iframe src="https://www.youtube.com/embed/aqz-KE-bpKQ" onload="alert(1)"><script>alert(2)</script></iframe>
			<iframe src="https://www.youtube.com/embed/x&quot;&gt;&lt;script&gt;alert(3)&lt;/script&gt;"></iframe>
			""");

		assertThat(clean).doesNotContain("<script").doesNotContain("alert").doesNotContain("onload");
		assertThat(clean).contains("src=\"https://www.youtube.com/embed/aqz-KE-bpKQ\"");
		// O embed com endereco adulterado (entidade no lugar de aspas) nao vira iframe nenhum.
		assertThat(clean.split("<iframe", -1).length - 1).isEqualTo(1);
	}

	@Test
	void linkToVideoStaysALinkForTheAppToTurnIntoAPlayer() {
		String clean = SafeHtml.sanitizePageHtml("<p>Veja <a href=\"https://youtu.be/aqz-KE-bpKQ\">o video</a></p>");

		assertThat(clean).contains("href=\"https://youtu.be/aqz-KE-bpKQ\"");
		assertThat(clean).contains("o video");
	}

	/**
	 * Relato da Mel (2026-10-20): a prévia (HTML cru, no editor) mostrava o SVG animado, mas o
	 * perfil saía sem o desenho e sem as animações. A allowlist não conhecia SVG e o jsoup apagava
	 * o desenho inteiro na gravação.
	 */
	@Test
	void inlineSvgAndItsAnimationsSurviveTheSanitizer() {
		String clean = SafeHtml.sanitizeProfileHtml("""
			<style>
			  @keyframes girar { to { transform: rotate(360deg); } }
			  .roda { animation: girar 4s linear infinite; transform-origin: center; }
			</style>
			<svg viewBox="0 0 100 100" width="120">
			  <defs><linearGradient id="g"><stop offset="0" stop-color="#7c3aed"/></linearGradient></defs>
			  <g class="roda">
			    <circle cx="50" cy="50" r="40" fill="url(#g)"/>
			    <animate attributeName="r" from="40" to="45" dur="2s" repeatCount="indefinite"/>
			    <animateTransform attributeName="transform" type="rotate" from="0 50 50" to="360 50 50" dur="6s" repeatCount="indefinite"/>
			  </g>
			</svg>
			""");

		assertThat(clean).contains("<svg").contains("viewBox=\"0 0 100 100\"");
		assertThat(clean).contains("<circle").contains("fill=\"url(#g)\"");
		assertThat(clean).contains("stop-color=\"#7c3aed\"");
		// SMIL e a animação em CSS que dá o movimento no desenho.
		assertThat(clean).contains("<animate ").contains("<animateTransform ");
		assertThat(clean).contains("@keyframes girar").contains("animation: girar");
	}

	@Test
	void svgReferencesStayInsideTheDocumentAndMediaFollowsTheImageRule() {
		String clean = SafeHtml.sanitizeProfileHtml("""
			<svg viewBox="0 0 10 10">
			  <symbol id="estrela"><path d="M0 0h10v10z"/></symbol>
			  <use href="#estrela"/>
			  <use href="https://malicioso.test/externo.svg#x"/>
			  <use xlink:href="#estrela"/>
			  <image href="https://enois.ia.br/foto.png" width="10" height="10"/>
			  <image href="javascript:alert(1)"/>
			</svg>
			""");

		// Referência interna fica; externa perde o endereço (deixa de buscar outro documento).
		assertThat(clean).contains("href=\"#estrela\"");
		assertThat(clean).doesNotContain("malicioso.test").doesNotContain("externo.svg");
		// Imagem dentro do SVG segue a régua do <img>.
		assertThat(clean).contains("href=\"https://enois.ia.br/foto.png\"");
		assertThat(clean).doesNotContain("javascript:");
	}

	@Test
	void svgCannotSmuggleScriptOrHtml() {
		String clean = SafeHtml.sanitizePageHtml("""
			<svg onload="alert(1)">
			  <script>alert(2)</script>
			  <foreignObject><div onclick="alert(3)">html</div></foreignObject>
			  <a href="javascript:alert(4)">clique</a>
			</svg>
			""");
		String checked = clean == null ? "" : clean;

		assertThat(checked).doesNotContain("<script").doesNotContain("alert")
			.doesNotContain("onload").doesNotContain("onclick").doesNotContain("<foreignObject")
			.doesNotContain("javascript:");
	}

	/**
	 * O atributo `id` e ALVO de estilizacao, nao decoracao: `#hero{...}` no <style>, `url(#grad)` no
	 * SVG e `href="#secao"` nos links apontam para ele.
	 *
	 * <p>Sem `id` na allowlist, o sanitizador apagava o atributo e mantinha o CSS/referencia — a bio
	 * saia sem o estilo que a pessoa escreveu, e o gradiente do SVG perdia o preenchimento (o
	 * `fill="url(#g)"` ficava apontando para um id que nao existia mais). E o mesmo tipo de quebra do
	 * relato do SVG animado (2026-10-20): previa certa, perfil errado.</p>
	 */
	@Test
	void idAttributeSurvivesBecauseCssAndSvgReferenceIt() {
		String clean = SafeHtml.sanitizeProfileHtml("""
			<style>#hero{background:linear-gradient(45deg,#7c38ee,#ff0080);border-radius:16px}</style>
			<div id="hero">Meu perfil</div>
			""");

		assertThat(clean).as("o id e o alvo do seletor #hero").contains("id=\"hero\"");
		assertThat(clean).contains("#hero{");
	}

	@Test
	void svgGradientKeepsTheIdItsReferencesUse() {
		String clean = SafeHtml.sanitizeProfileHtml("""
			<svg viewBox="0 0 100 100">
			  <defs><linearGradient id="g"><stop offset="0" stop-color="#7c3aed"/></linearGradient></defs>
			  <circle cx="50" cy="50" r="40" fill="url(#g)"/>
			</svg>
			""");

		assertThat(clean).as("o gradiente precisa do id para o fill url(#g) resolver")
			.contains("id=\"g\"").contains("url(#g)");
	}

	/** `id` e atributo de valor: permiti-lo nao pode abrir a porta de escrever markup. */
	@Test
	void idValueCannotEscapeTheAttribute() {
		String clean = SafeHtml.sanitizeProfileHtml("""
			<div id="x&quot; onclick=&quot;alert(1)">a</div>
			<div id="a><script>alert(2)</script>">b</div>
			""");

		assertThat(clean).as("o jsoup reserializa o valor, nao o interpreta")
			.doesNotContain("<script").doesNotContain("onclick=\"alert");
	}
}
