package com.socialcoisa.rede.shared.html;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Limpeza do HTML das "paginas" publicadas pelos usuarios.
 *
 * O render acontece num iframe com sandbox e sem scripts (nem o nosso front o executa), entao o
 * risco ja e baixo por construcao. Ainda assim o markup e limpo aqui, na entrada, por tres
 * motivos: o que fica guardado nao depende de quem renderiza depois (app, e-mail, crawler),
 * o conteudo cortado e o mesmo para todos os clientes, e um sanitizador conhecido e mais facil
 * de auditar do que confiar so no sandbox.
 *
 * Estrategia: allowlist de tags/atributos (jsoup Safelist, que tambem remove atributos de evento
 * e protocolos perigosos como javascript:) + limpeza de CSS (sem @import, expression(), url()
 * para fora de https:/data:), porque o style inline e o unico jeito do usuario dar aparencia a
 * pagina e sem ele o recurso perde sentido.
 */
public final class SafeHtml {

	/**
	 * Teto de HTML renderizado por pagina, em caracteres, para TODO MUNDO: nao ha excecao por
	 * reputacao, tipo de conta ou idade da conta, e o mesmo numero vale na publicacao e na edicao.
	 * O front usa o mesmo valor no campo de escrita (`PAGE_HTML_MAX_LENGTH`), para ninguem digitar
	 * algo que o servidor vai recusar depois. O numero e o MESMO da bio do perfil, de proposito:
	 * um teto unico para "HTML colado" e mais facil de explicar do que um por superficie.
	 */
	/**
	 * Teto do HTML de uma pagina. Subiu de 20k para 200k porque um SVG colado (ilustracao com
	 * gradientes e animacao) passa facil de 20 mil caracteres — o desenho era recusado antes mesmo
	 * de o sanitizador olhar as tags. A coluna e longtext; quem segura abuso e o rate limit.
	 */
	public static final int MAX_PAGE_HTML = 200_000;

	/**
	 * Teto do HTML da bio do perfil. E o mesmo para todo mundo, como o da pagina; e menor porque o
	 * perfil e aberto em muito mais telas (lista de conexoes, busca, sugestoes) do que um post.
	 * O front espelha esse numero em `PROFILE_HTML_MAX_LENGTH`.
	 */
	public static final int MAX_PROFILE_HTML = 20_000;

	/**
	 * Teto do HTML de um card de projeto. O mesmo número da bio e da página: um teto
	 * único para "HTML colado" continua sendo o mais fácil de explicar e de conferir.
	 */
	/**
	 * Teto do HTML do card de projeto. Mesmo motivo do teto da pagina: um SVG colado passa de
	 * 20 mil caracteres e era recusado antes do sanitizador olhar as tags.
	 */
	public static final int MAX_PROJECT_HTML = 200_000;

	/** Teto do HTML de blog (tema do cantinho ou post em HTML): o mesmo do resto, sem excecao. */
	public static final int MAX_BLOG_HTML = 20_000;

	/**
	 * Corte do texto cru antes de parsear: evita gastar CPU com um paste gigante. Fica acima do
	 * teto porque a limpeza costuma encolher o markup (scripts fora, tags nao permitidas fora).
	 */
	private static final int MAX_RAW_INPUT = 400_000;

	/**
	 * Teto do CSS que passa pela limpeza. O teto do documento bruto e 400 KB, mas uma folha de estilo
	 * desse tamanho nao e conteudo real — e entrada escolhida para custar CPU. Acima disso a recusa e
	 * explicita, em vez de gastar o tempo de uma thread de requisicao.
	 */
	private static final int MAX_CSS_INPUT = 64_000;

	private static final Pattern STYLE_IMPORT = Pattern.compile("(?is)@import\\b[^;]*;?");
	/**
	 * Token de CSS perigoso (a palavra, nao a declaracao inteira).
	 *
	 * Aqui NAO existe o prefixo ganancioso `[^;{}]*`: com ele o motor tentava cada inicio possivel do
	 * trecho e o custo crescia com o quadrado do tamanho da entrada. Quem apaga a declaracao inteira
	 * agora e {@link #stripDangerous}, em uma passada linear.
	 */
	// `behavior:` com lookbehind: sem ele, `scroll-behavior: smooth` (CSS legitimo e comum em
	// paginas animadas) casava no "behavior:" e a declaracao inteira era descartada.
	private static final Pattern CSS_DANGEROUS_TOKEN = Pattern.compile(
		"(?is)expression\\s*\\(|javascript\\s*:\\s*|vbscript\\s*:|-moz-binding\\s*:"
			+ "|(?<![a-zA-Z-])behavior\\s*:|image-set\\s*\\(");
	/** CSS escape: a backslash plus 1-6 hex digits (and one optional whitespace), or plus any other char. */
	private static final Pattern CSS_ESCAPE = Pattern.compile("\\\\(?:([0-9a-fA-F]{1,6})[ \\t\\r\\n\\f]?|([^0-9a-fA-F\\r\\n\\f]))");
	/**
	 * `url(...)`. As classes de caractere sao possessivas e excluem `(` e as aspas: o argumento de um
	 * `url()` nao pode conter nenhum deles, entao o casamento continua o mesmo — mas a varredura para
	 * no primeiro delimitador em vez de percorrer o resto da folha a cada candidato.
	 */
	private static final Pattern STYLE_URL = Pattern
		.compile("(?is)url\\s*\\(\\s*(?:\"([^\"]*+)\"|'([^']*+)'|([^'\"()\\s]*+))\\s*\\)");
	private static final Pattern STYLE_ATTRIBUTE = Pattern.compile("(?is)\\sstyle\\s*=\\s*(\"([^\"]*)\"|'([^']*)')");

	/** Tag de embed de terceiro (quadro/objeto) com o endereco numa das duas formas usadas. */
	private static final Pattern EMBED_TAG = Pattern
		.compile("(?is)<(?:iframe|embed|object)\\b[^>]*?(?:src|data)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')[^>]*>");

	/**
	 * Endereco de embed aceito: https, provedor conhecido, sem aspas/espacos/angulos. A checagem e
	 * por host inteiro (com `\.`) e o resto do caminho nao pode conter caractere que quebraria o
	 * atributo — assim o valor pode ser reescrito dentro do HTML sem risco de injecao.
	 */
	private static final Pattern PROVIDER_EMBED_URL = Pattern.compile(
		"(?i)^https://(?:www\\.)?(?:youtube\\.com|youtube-nocookie\\.com|youtu\\.be|player\\.vimeo\\.com|vimeo\\.com"
			+ "|dailymotion\\.com|dai\\.ly|player\\.twitch\\.tv|twitch\\.tv|open\\.spotify\\.com"
			+ "|w\\.soundcloud\\.com|soundcloud\\.com)/[^\\s\"'<>]*$");

	/**
	 * Sequencia com cara de entidade HTML (`&quot;`, `&#39;`, `&amp;`) no endereco. Endereco de
	 * embed de verdade nao carrega isso — quem carrega esta tentando fechar o atributo e escrever
	 * markup. Serve de segunda barreira, alem do escape.
	 */
	private static final Pattern ENTITY_LIKE = Pattern.compile("&(?:#[0-9]+|[a-zA-Z][a-zA-Z0-9]{1,30});");

	/** Quadro (para descartar o que nao for embed de provedor) e o `src` dentro dele. */
	private static final Pattern IFRAME_TAG = Pattern.compile("(?is)<iframe\\b[^>]*>");
	private static final Pattern IFRAME_SRC = Pattern.compile("(?is)\\ssrc\\s*=\\s*(\"([^\"]*)\"|'([^']*)')");

	/** Tag de link aberta, para reescrever alvo e `rel`. */
	private static final Pattern LINK_TAG = Pattern.compile("(?is)<a\\b[^>]*>");

	/** Tags de SVG que REFERENCIAM outro documento (só o próprio, por fragmento, é aceito). */
	private static final Pattern SVG_REF_TAG = Pattern.compile("(?is)<(?:use|mpath)\\b[^>]*>");
	private static final Pattern HREF_ATTRIBUTE = Pattern
		.compile("(?is)\\s(?:xlink:)?href\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')");

	/** Atributo que marca "aqui vai um player"; o front casa esse marcador com o player do app. */
	private static final String EMBED_MARKER_ATTRIBUTE = "data-enois-embed";

	private static final Safelist PAGE_ALLOWLIST = buildAllowlist();

	private SafeHtml() {
	}

	/**
	 * Sanitiza o HTML de uma pagina. Devolve {@code null} quando nao sobra nada util.
	 * O conteudo do {@code <style>} do documento colado e preservado e reanexado no inicio.
	 */
	public static String sanitizePageHtml(String raw) {
		return sanitize(raw, MAX_PAGE_HTML, "PAGE_HTML_TOO_LONG", "HTML da pagina");
	}

	/** HTML obrigatorio (posts do tipo PAGE): mesma limpeza, mas recusa quando nao sobra nada. */
	public static String requirePageHtml(String raw) {
		String sanitized = sanitizePageHtml(raw);
		if (sanitized == null) {
			throw new ApiException(400, "INVALID_PAGE_HTML", "Pagina precisa de HTML valido.");
		}
		return sanitized;
	}

	/**
	 * HTML da bio do perfil: mesma limpeza e mesma renderizacao isolada do post de pagina, com teto
	 * proprio. Devolve {@code null} quando nao sobra nada util (o perfil volta a mostrar a bio de
	 * texto, que continua existindo ao lado).
	 */
	public static String sanitizeProfileHtml(String raw) {
		return sanitize(raw, MAX_PROFILE_HTML, "PROFILE_HTML_TOO_LONG", "HTML do perfil");
	}

	/** HTML obrigatório do card de projeto: mesma limpeza da família, teto próprio. */
	public static String sanitizeProjectHtml(String raw) {
		return sanitize(raw, MAX_PROJECT_HTML, "PROJECT_HTML_TOO_LONG", "HTML do projeto");
	}

	public static String requireProfileHtml(String raw) {
		String sanitized = sanitizeProfileHtml(raw);
		if (sanitized == null) {
			throw new ApiException(400, "INVALID_PROFILE_HTML", "Bio em HTML precisa de conteudo valido.");
		}
		return sanitized;
	}

	/**
	 * HTML do blog: o tema do cantinho e o corpo de um post escrito em HTML. Mesma limpeza e mesmo
	 * teto do resto (um limite so, para todo mundo) — o que muda e so a mensagem de erro, que fala
	 * de blog em vez de pagina/perfil.
	 */
	public static String sanitizeBlogHtml(String raw) {
		return sanitize(raw, MAX_BLOG_HTML, "BLOG_HTML_TOO_LONG", "HTML do blog");
	}

	public static String requireBlogHtml(String raw) {
		String sanitized = sanitizeBlogHtml(raw);
		if (sanitized == null) {
			throw new ApiException(400, "INVALID_BLOG_HTML", "Post do blog precisa de HTML valido.");
		}
		return sanitized;
	}

	/**
	 * Limpeza em si, compartilhada entre pagina e bio: allowlist de tags/atributos, protocolos
	 * restritos, CSS sem import/expression/url remota, e teto aplicado sobre o RESULTADO (que e o
	 * valor guardado e renderizado).
	 */
	private static String sanitize(String raw, int maxLength, String tooLongCode, String label) {
		if (!StringUtils.hasText(raw)) {
			return null;
		}
		if (raw.length() > MAX_RAW_INPUT) {
			throw new ApiException(400, tooLongCode, label + " e grande demais.");
		}

		StyleDocument document = splitStyles(raw);
		String css = sanitizeCss(document.css());
		// O <style> sai do markup antes da limpeza e volta sanitizado, no formato que o front espera.
		String withoutStyles = document.markup();
		// Embed de provedor conhecido e PRESERVADO (com o endereco validado), porque a pagina e servida
		// na origem de conteudo: ali o quadro pode rodar o script do player sem que isso signifique
		// acesso ao app. Host desconhecido sai fora.
		String withMarkers = EMBED_TAG.matcher(withoutStyles)
			.replaceAll(match -> keepProviderEmbed(match.group(1) != null ? match.group(1) : match.group(2)));
		Document clean = new Cleaner(PAGE_ALLOWLIST).clean(Jsoup.parse(withMarkers));
		clean.outputSettings().prettyPrint(false);
		normalizeAutoplay(clean);

		String markup = hardenSvgReferences(hardenLinks(dropUnknownIframes(clean.body().html().trim())));
		String result = (css.isEmpty() ? "" : "<style>" + css + "</style>") + sanitizeStyleAttributes(markup);
		if (result.length() > maxLength) {
			throw new ApiException(400, tooLongCode,
				label + " deve ter no maximo " + maxLength + " caracteres.");
		}
		return result.isEmpty() ? null : result;
	}

	/**
	 * Mantem o embed de provedor conhecido, com o endereco validado; host desconhecido sai fora.
	 *
	 * Player de terceiros exige script, e a pagina NAO roda script proprio (`script-src 'none'` na
	 * politica servida) — o script do player roda no documento dele, dentro do quadro do provedor.
	 * Como a pagina e servida numa origem separada da do app, ter script aqui dentro nao da acesso a
	 * sessao de ninguem. O endereco e restrito a host de provedor conhecido, em https, sem aspas ou
	 * entidade HTML no meio (o valor e reserializado pelo jsoup depois disso).
	 */
	private static String keepProviderEmbed(String url) {
		if (url == null || !PROVIDER_EMBED_URL.matcher(url).matches() || ENTITY_LIKE.matcher(url).find()) {
			return "";
		}
		// `allow` delega ao player do provedor o autoplay que o autor pediu na URL (ex.: o
		// autoplay=1 do YouTube) e a tela cheia; o navegador decide se honra (com som, so toca
		// depois de um gesto do usuario na pagina).
		return "<iframe src=\"" + escapeAttribute(url) + "\" allow=\"autoplay; fullscreen; encrypted-media\" "
			+ "allowfullscreen loading=\"lazy\" referrerpolicy=\"no-referrer\"></iframe>";
	}

	/** Escape minimo de valor de atributo: `&` primeiro, senao os outros escapes sao re-escapes. */
	private static String escapeAttribute(String value) {
		return value.replace("&", "&amp;")
			.replace("\"", "&quot;")
			.replace("'", "&#39;")
			.replace("<", "&lt;")
			.replace(">", "&gt;");
	}

	/**
	 * Quadro que nao seja embed de provedor conhecido sai fora.
	 *
	 * A allowlist permite `iframe` porque o embed de provedor fica (o player roda dentro da pagina),
	 * mas quem garante isso e esta passagem: sobrou `<iframe>` sem `src` de provedor — inclusive o
	 * caso de `srcdoc`, cujo atributo cai na limpeza — e o quadro inteiro e descartado.
	 */
	private static String dropUnknownIframes(String markup) {
		return IFRAME_TAG.matcher(markup).replaceAll(match -> {
			Matcher src = IFRAME_SRC.matcher(match.group(0));
			if (!src.find()) {
				return "";
			}
			String url = src.group(2) != null ? src.group(2) : src.group(3);
			return url != null && PROVIDER_EMBED_URL.matcher(url).matches() && !ENTITY_LIKE.matcher(url).find()
				? match.group(0)
				: "";
		});
	}

	/**
	 * Autoplay pedido pelo autor precisa de ajuda para acontecer de verdade: com som, os navegadores
	 * bloqueiam (so tocam depois de um gesto do usuario), entao video com `autoplay` e sem `muted`
	 * nunca tocaria — recebe `muted` + `playsinline`, o padrao de "tocar sozinho", que vale ate
	 * dentro do quadro sandboxed. Audio silenciado nao faz sentido (muted e nao tocar); se o
	 * navegador recusar o autoplay, `controls` garante que o som nao fique preso atras de um player
	 * invisivel. Quem ja escreveu o par completo nao e reescrito.
	 */
	private static void normalizeAutoplay(Document clean) {
		for (Element video : clean.select("video[autoplay]")) {
			if (!video.hasAttr("muted")) {
				video.attr("muted", "muted");
			}
			if (!video.hasAttr("playsinline")) {
				video.attr("playsinline", "playsinline");
			}
		}
		for (Element audio : clean.select("audio[autoplay]")) {
			if (!audio.hasAttr("controls")) {
				audio.attr("controls", "controls");
			}
		}
	}

	/**
	 * Links da pagina: alvo e `rel` sao definidos por NOS, nao pelo autor.
	 *
	 * Sem isso, o autor escolhe `target` — e `_top` existe. O sandbox ja bloqueia navegacao da aba
	 * de cima, mas nao depender de uma unica barreira e o ponto: aqui o valor nem chega a existir.
	 * `noopener`/`noreferrer` tambem cortam a aba aberta de mexer na origem de quem clicou.
	 */
	private static String hardenLinks(String markup) {
		return LINK_TAG.matcher(markup).replaceAll(match -> {
			String tag = match.group(0).replaceAll("(?i)\\s(?:target|rel)\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)", "");
			return tag.replaceFirst("(?i)<a\\b", "<a target=\"_blank\" rel=\"noopener noreferrer\"");
		});
	}

	/**
	 * Referências de SVG ({@code <use>}/{@code <mpath>}) só valem para o PRÓPRIO documento
	 * ({@code href="#id"}). Um endereço externo faria o navegador de quem lê buscar outro arquivo —
	 * requisição que o leitor não vê e que denuncia para o dono do endereço que a página foi aberta.
	 * `<image>` continua com https/data (é mídia visível, como o `<img>`), pelo protocolo da lista.
	 */
	private static String hardenSvgReferences(String markup) {
		return SVG_REF_TAG.matcher(markup).replaceAll(tag -> HREF_ATTRIBUTE.matcher(tag.group(0))
			.replaceAll(href -> {
				String url = href.group(1) != null ? href.group(1) : href.group(2);
				return url != null && url.trim().startsWith("#") ? href.group(0) : "";
			}));
	}

	/**
	 * Separa o documento colado em markup (sem os blocos {@code <style>}) e a folha de estilo junta.
	 *
	 * A varredura anterior era um regex com `(.*?)</style>`: sem o fechamento, cada abertura relia o
	 * resto da entrada — quadratico. Aqui os indices so caminham para frente, e a ausencia de um
	 * fechamento encerra a busca de uma vez.
	 */
	private static StyleDocument splitStyles(String raw) {
		String lower = raw.toLowerCase(Locale.ROOT);
		StringBuilder markup = new StringBuilder(raw.length());
		StringBuilder css = new StringBuilder();
		int cursor = 0;
		int search = 0;
		while (true) {
			int open = lower.indexOf("<style", search);
			if (open < 0) {
				break;
			}
			if (!isStyleOpen(lower, open)) {
				search = open + "<style".length();
				continue;
			}
			int openEnd = lower.indexOf('>', open);
			if (openEnd < 0) {
				break;
			}
			int close = lower.indexOf("</style", openEnd);
			if (close < 0) {
				break;
			}
			int closeEnd = lower.indexOf('>', close);
			if (closeEnd < 0) {
				break;
			}
			markup.append(raw, cursor, open);
			css.append(raw, openEnd + 1, close).append('\n');
			cursor = closeEnd + 1;
			search = cursor;
		}
		markup.append(raw, cursor, raw.length());
		return new StyleDocument(markup.toString(), css.toString());
	}

	/** {@code <style>} e nao {@code <styleX>}: o nome da tag acaba no `>` ou num espaco. */
	private static boolean isStyleOpen(String lower, int open) {
		int after = open + "<style".length();
		if (after >= lower.length()) {
			return true;
		}
		char next = lower.charAt(after);
		return next == '>' || next == '/' || Character.isWhitespace(next);
	}

	private record StyleDocument(String markup, String css) {
	}

	/** Remove do CSS o que pode sair da caixa: importacao remota, expressao e url() nao-https. */
	private static String sanitizeCss(String css) {
		if (!StringUtils.hasText(css)) {
			return "";
		}
		if (css.length() > MAX_CSS_INPUT) {
			throw new ApiException(400, "CSS_TOO_LONG",
				"CSS deve ter no maximo " + MAX_CSS_INPUT + " caracteres.");
		}
		String out = stripComments(css);
		out = decodeIdentifierEscapes(out).replace("<", "");
		out = STYLE_IMPORT.matcher(out).replaceAll("");
		out = stripDangerous(out);
		out = STYLE_URL.matcher(out).replaceAll(match -> {
			String target = firstNonNull(match.group(1), match.group(2), match.group(3)).trim().toLowerCase(Locale.ROOT);
			// CSS so carrega `data:`: um `url(https://...)` e requisicao invisivel — o leitor nao ve
			// nada na tela e o dono do endereco descobre que a pagina foi aberta (e de onde). Imagem
			// remota continua permitida por `<img>`, que aparece no markup e pode ser inspecionada.
			// O custo e nao dar para usar imagem remota de fundo por CSS.
			return target.startsWith("data:") ? match.group(0) : "";
		});
		return out.trim();
	}

	private static String firstNonNull(String... values) {
		for (String value : values) {
			if (value != null) {
				return value;
			}
		}
		return "";
	}

	/**
	 * Tira os comentarios `/* ... *&#47;` em uma passada. O regex anterior (`.*?` com alternativa `$`)
	 * relia o resto da folha a cada abertura quando nao havia fechamento.
	 */
	private static String stripComments(String css) {
		int open = css.indexOf("/*");
		if (open < 0) {
			return css;
		}
		StringBuilder out = new StringBuilder(css.length());
		int from = 0;
		while (open >= 0) {
			out.append(css, from, open);
			int close = css.indexOf("*/", open + 2);
			if (close < 0) {
				return out.toString();
			}
			from = close + 2;
			open = css.indexOf("/*", from);
		}
		out.append(css, from, css.length());
		return out.toString();
	}

	/**
	 * Apaga a declaracao inteira que contem um token perigoso.
	 *
	 * O {@link #CSS_DANGEROUS_TOKEN} marca a palavra; o trecho apagado vai do ultimo `;`/`{`/`}` antes
	 * dela ate o proximo `;`/`{`/`}`, incluindo o `;` — o mesmo recorte do regex antigo, agora em O(N)
	 * em vez de quadratico. A varredura para tras nunca passa do ponto ja copiado, entao cada
	 * caractere da entrada e lido um numero constante de vezes.
	 */
	private static String stripDangerous(String css) {
		Matcher matcher = CSS_DANGEROUS_TOKEN.matcher(css);
		StringBuilder out = null;
		int copied = 0;
		while (matcher.find()) {
			int start = Math.max(copied, declarationStart(css, matcher.start(), copied));
			int end = declarationEnd(css, matcher.end());
			if (end <= start) {
				continue;
			}
			if (out == null) {
				out = new StringBuilder(css.length());
			}
			out.append(css, copied, start);
			copied = end;
		}
		if (out == null) {
			return css;
		}
		out.append(css, copied, css.length());
		return out.toString();
	}

	private static int declarationStart(String css, int index, int floor) {
		int start = index;
		while (start > floor && !isDeclarationBoundary(css.charAt(start - 1))) {
			start--;
		}
		return start;
	}

	private static int declarationEnd(String css, int from) {
		int end = from;
		while (end < css.length() && !isDeclarationBoundary(css.charAt(end))) {
			end++;
		}
		if (end < css.length() && css.charAt(end) == ';') {
			end++;
		}
		return end;
	}

	private static boolean isDeclarationBoundary(char c) {
		return c == ';' || c == '{' || c == '}';
	}

	/**
	 * Escapes and comments let a name be spelled so the filters above do not see it (a backslash-escaped
	 * `url(`, a comment inside `expression(`) while the browser still reads it. Escapes that stand for a name character
	 * are decoded, so every check runs on what the browser sees; other escapes (quotes, newlines,
	 * non-ASCII in `content`) are kept as written, since decoding them could change the meaning.
	 */
	private static String decodeIdentifierEscapes(String css) {
		return CSS_ESCAPE.matcher(css).replaceAll(match -> {
			int codePoint = match.group(1) != null ? Integer.parseInt(match.group(1), 16) : match.group(2).codePointAt(0);
			boolean nameChar = codePoint < 128 && (Character.isLetterOrDigit(codePoint) || codePoint == '-' || codePoint == '_');
			return Matcher.quoteReplacement(nameChar ? Character.toString(codePoint) : match.group(0));
		});
	}

	private static String sanitizeStyleAttributes(String markup) {
		return STYLE_ATTRIBUTE.matcher(markup).replaceAll(match -> {
			String value = match.group(2) != null ? match.group(2) : match.group(3);
			String cleaned = sanitizeCss(value).replace("\"", "'").replace("&quot;", "'");
			return cleaned.isEmpty() ? "" : " style=\"" + cleaned + "\"";
		});
	}

	/**
	 * Allowlist de markup. {@code addProtocols} e protegido no jsoup, entao a lista e montada numa
	 * subclasse — e ela nao e opcional: sem regra de protocolo para {@code a[href]}/{@code img[src]},
	 * o jsoup aceita qualquer esquema, inclusive {@code javascript:}.
	 */
	private static final class PageSafelist extends Safelist {

		PageSafelist() {
			// `video`, `audio` e `picture` entram porque sao a forma de midia que roda no navegador
			// SEM script: o player e o proprio navegador (controles nativos), entao a promessa de
			// "nenhum script executa" continua valendo com midia embutida.
			addTags("a", "abbr", "article", "aside", "audio", "b", "blockquote", "br", "caption", "cite", "code",
				"iframe",
				"col", "colgroup", "dd", "del", "details", "dfn", "div", "dl", "dt", "em", "figcaption", "figure",
				"footer", "h1", "h2", "h3", "h4", "h5", "h6", "header", "hr", "i", "img", "ins", "kbd", "li",
				"main", "mark", "ol", "p", "picture", "pre", "q", "s", "samp", "section", "small", "source",
				"span", "strong", "sub", "summary", "sup", "table", "tbody", "td", "tfoot", "th", "thead", "time",
				"tr", "track", "u", "ul", "video", "wbr");
			addAttributes(":all", "class", "style", "title", "dir", "lang", "align", "width", "height",
				EMBED_MARKER_ATTRIBUTE);
			addAttributes("a", "href");
			addAttributes("img", "src", "alt", "loading");
			addAttributes("iframe", "src", "allow", "allowfullscreen", "loading", "referrerpolicy");
			addAttributes("video", "src", "poster", "controls", "autoplay", "preload", "loop", "muted",
				"playsinline");
			addAttributes("audio", "src", "controls", "autoplay", "preload", "loop", "muted");
			addAttributes("source", "src", "type", "media");
			addAttributes("track", "src", "kind", "srclang", "label", "default");
			addAttributes("ol", "start", "type", "reversed");
			addAttributes("li", "value");
			addAttributes("table", "border", "cellpadding", "cellspacing");
			addAttributes("td", "colspan", "rowspan", "headers");
			addAttributes("th", "colspan", "rowspan", "scope", "headers");
			addProtocols("a", "href", "http", "https", "mailto");
			// Midia e documento so por https (ou embutido em data:). Endereco http nao carregaria de
			// qualquer forma numa pagina https — o navegador bloqueia como conteudo misto — e deixar o
			// atributo passar so esconderia o problema. `<iframe>`/`<embed>`/`<object>` ficam de fora:
			// o visualizador de PDF do Chrome nao roda dentro do sandbox (conferido no navegador), e
			// entao documento fica como link, que abre no visualizador nativo em outra aba.
			addProtocols("img", "src", "https", "data");
			addProtocols("video", "src", "https", "data");
			addProtocols("video", "poster", "https", "data");
			addProtocols("audio", "src", "https", "data");
			addProtocols("source", "src", "https", "data");
			addProtocols("track", "src", "https");
			// O src do iframe so passa em https, e a checagem de host acontece antes (keepProviderEmbed).
			addProtocols("iframe", "src", "https");

			// ===== SVG INLINE =====
			// Desenho animado colado costuma ser SVG (com CSS e animação SMIL). Sem estas tags o
			// sanitizador apagava o desenho INTEIRO: a prévia (HTML cru, no editor) mostrava tudo e
			// o perfil aparecia sem o SVG e sem as animações. SVG é markup inerte — quem executa
			// script seria `<script>`, que continua fora da lista (o jsoup preserva a caixa dos
			// nomes: `viewBox`, `linearGradient`, `attributeName` seguem como o navegador espera).
			addTags("svg", "g", "defs", "symbol", "use", "mpath", "marker", "pattern", "mask", "clippath", "view",
				"path", "rect", "circle", "ellipse", "line", "polyline", "polygon",
				"text", "tspan", "textpath", "desc",
				"lineargradient", "radialgradient", "stop",
				"filter", "fegaussianblur", "feoffset", "feblend", "fecolormatrix", "fecomposite", "femerge",
				"femergenode", "feflood", "fedropshadow", "feturbulence", "fedisplacementmap",
				// Animação declarativa (SMIL): roda sem script, é o que faz o desenho se mexer.
				"animate", "animatetransform", "animatemotion", "set",
				// `image` dentro de SVG carrega https/data (mesma regra do <img>), declarada abaixo.
				// Fora de propósito: `foreignObject` (embute HTML de novo, com as regras do HTML),
				// `script` e qualquer atributo de evento — os testes cobrem o descarte dos três.
				"image");
			addAttributes(":all",
				// Forma, cor e traço.
				"fill", "fill-opacity", "fill-rule", "stroke", "stroke-width", "stroke-linecap", "stroke-linejoin",
				"stroke-dasharray", "stroke-dashoffset", "stroke-opacity", "opacity", "color", "display",
				"visibility", "transform", "transform-origin", "transform-box", "clip-path", "clip-rule", "mask",
				"filter", "vector-effect", "paint-order", "mix-blend-mode", "isolation",
				// Geometria.
				"d", "x", "y", "x1", "y1", "x2", "y2", "cx", "cy", "r", "rx", "ry", "points", "viewbox",
				"preserveaspectratio", "pathlength",
				// Texto.
				"font-family", "font-size", "font-weight", "font-style", "text-anchor", "dominant-baseline",
				"dx", "dy", "rotate", "letter-spacing", "text-decoration", "startoffset", "textlength",
				"lengthadjust",
				// Gradientes, padrões e filtros.
				"offset", "stop-color", "stop-opacity", "gradientunits", "gradienttransform", "spreadmethod",
				"patternunits", "patterncontentunits", "patterntransform",
				"in", "in2", "result", "stddeviation", "mode", "values", "flood-color", "flood-opacity",
				"preservealpha", "kernelmatrix", "order", "radius", "scale", "basefrequency", "numoctaves", "seed",
				"operator", "k1", "k2", "k3", "k4",
				// Marcadores, máscaras e recortes.
				"markerwidth", "markerheight", "refx", "refy", "orient", "markerunits", "clip-pathunits",
				"maskunits", "maskcontentunits",
				// SMIL.
				"attributename", "attributetype", "from", "to", "by", "begin", "dur", "end", "repeatcount",
				"repeatdur", "keytimes", "keysplines", "calcmode", "additive", "accumulate", "restart");
			// Referências: `image`/`use` seguem a mesma régua do <img> (https ou data:), e o `use`
			// ainda passa por um corte depois (só fragmento do próprio documento — ver
			// hardenSvgReferences).
			addAttributes("image", "href", "xlink:href");
			addAttributes("use", "href", "xlink:href");
			addAttributes("mpath", "href", "xlink:href");
			addProtocols("image", "href", "https", "data");
			addProtocols("image", "xlink:href", "https", "data");
		}
	}

	private static Safelist buildAllowlist() {
		return new PageSafelist();
	}
}
