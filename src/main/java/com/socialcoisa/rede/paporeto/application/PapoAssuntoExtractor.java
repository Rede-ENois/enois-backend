package com.socialcoisa.rede.paporeto.application;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * O extrator de assuntos do Papo Reto: transforma um texto público em expressões que nomeiam
 * a conversa — SEM rede, SEM modelo, decidido localmente.
 *
 * Regras do jogo (determinístico e auditável):
 * - o texto é LIMPO antes de virar palavra: link, e-mail, menção, emote de chat e sobra de HTML
 *   saem inteiros ("http", "www" e domínio não nomeiam conversa — foi o bug do "http" em alta);
 * - o texto é dobrado (NFD, sem acento, minúsculo) e quebrado em palavras, igual ao léxico do
 *   Termômetro, então "Inteligência Artificial" e "inteligencia artificial" são o MESMO assunto;
 * - palavras vazias (stopwords em português: artigos, preposições, conectivos, "muito", "tô")
 *   e tokens pequenos (menos de 3 letras) não viram assunto — é o "que", "de", "pra" saindo;
 *   riso ("kkkk", "hahaha"), número puro ("2026") e letra repetida ("assssss") também não;
 * - o extrator usa UNIGRAMAS e BIGRAMAS de palavras elegíveis ADJACENTES no texto — pontuação
 *   ou palavra vazia no meio quebra o par ("feei o meetup" não cola "feei meetup"), e palavra
 *   repetida não vira par consigo mesma ("praia praia" é um assunto só);
 * - o assunto do texto funde o bigrama com os seus unigramas: quando "inteligencia artificial"
 *   aparece junto, ela cobre "inteligencia" e "artificial" — o mais específico nomeia a
 *   conversa; unigrama que não entrou em bigrama nenhum é assunto próprio;
 * - as TAGS da publicação são assunto declarado pelo autor e entram como fala própria, sem
 *   depender de aparecerem no texto; o assunto da taxonomia (TECNOLOGIA, DESIGN…) fica de
 *   fora de propósito: é amplo demais e viraria alta de todo mundo;
 * - texto sem nenhuma palavra elegível não gera assunto nenhum (a maioria do feed é saudação
 *   ou link, e não vira "alta" nenhuma).
 */
@Component
public class PapoAssuntoExtractor {

	/** Maior expressão que um assunto tirado do texto pode ter (em palavras): bigrama. */
	public static final int MAXIMO_PALAVRAS = 2;

	/** Menor palavra elegível: "to", "eu", "o" não nomeiam conversa. */
	private static final int TAMANHO_MINIMO_PALAVRA = 3;

	/** Maior palavra elegível: o bagaço de 5000 letras de um post não é assunto, é ruído —
	 * e o unigrama tem de caber na coluna da fala (120), como o bigrama de 59+1+59. */
	public static final int TAMANHO_MAXIMO_PALAVRA = 59;

	/** O teto da expressão declarada (tag do autor): a coluna da fala é 120. */
	private static final int TAMANHO_MAXIMO_DECLARADO = 120;

	/**
	 * Maior expressão de um assunto DECLARADO: a tag do autor pode ser mais longa que um
	 * bigrama do texto ("trabalho remoto"), mas ainda é expressão curta, não frase.
	 */
	private static final int MAXIMO_PALAVRAS_DECLARADO = 3;

	/** O token do texto: letras e números, como no léxico do Termômetro. */
	private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}]+");

	/**
	 * As palavras vazias do português falado na rede: dobradas (sem acento, minúsculas).
	 * A lista é fechada a dedo — cada linha aqui é uma palavra que não nomeia assunto; não
	 * existe lista automática de negócio aqui.
	 */
	private static final Set<String> PALAVRAS_VAZIAS = palavrasVazias();

	/** Link inteiro: endereço, domínio e caminho não nomeiam conversa. */
	private static final Pattern LINK = Pattern.compile("(?iu)\\b(?:https?://|ftp://|www\\.)\\S+");

	/** Link em markdown: o rótulo é conteúdo; o endereço dentro dos parênteses é ruído. */
	private static final Pattern LINK_MARKDOWN = Pattern.compile("\\[([^\\]]*)\\]\\(([^)]*)\\)");

	/** E-mail: antes da menção, para não sobrar o domínio do endereço. */
	private static final Pattern EMAIL = Pattern.compile(
			"(?iu)[\\p{L}\\p{N}._%+-]+@[\\p{L}\\p{N}.-]+\\.[\\p{L}]{2,}");

	/** Menção: o @ é endereçamento, não assunto. */
	private static final Pattern MENCAO = Pattern.compile("(?u)@[\\p{L}\\p{N}_.-]+");

	/** Emote de chat (":KEKW:"): código do provedor, nunca conversa. */
	private static final Pattern EMOTE = Pattern.compile("(?u):[\\p{L}\\p{N}_+-]{1,32}:");

	/** HTML de página (o feed já sanitiza; a colagem pode trazer sobra). */
	private static final Pattern TAG_HTML = Pattern.compile("(?iu)</?[a-z][^>]{0,200}>");

	/** Domínio colado sem protocolo ("enois.ia.br", "site.com/pagina"). */
	private static final Pattern DOMINIO = Pattern.compile(
			"(?iu)\\b[\\p{L}\\p{N}-]+(?:\\.[\\p{L}\\p{N}-]+)*\\.[\\p{L}]{2,}(?:/\\S*)?");

	/** Riso, letra repetida e tecla presa: "kkkk", "hahaha", "assssss", "kkjkkj". */
	private static final Pattern RUISO = Pattern.compile(
			"(?u)^(?:k{2,}|(?:ha){2,}h?|(?:rs){2,}|(?:hu){2,}h?|(?:kkj)+k?|(.)\\1{2,})$");

	/** A fala extraída: a expressão dobrada e a fonte de onde ela veio (POST ou COMMENT). */
	public record Fala(String expressao, String fonte) {
	}

	/** Uma palavra do texto com a sua posição — é a posição que decide se duas são vizinhas. */
	private record Palavra(String texto, int inicio, int fim) {
	}

	/**
	 * A lista fechada das palavras que não nomeiam conversa, dobradas (sem acento, minúsculas).
	 * É montada como conjunto de verdade — a lista a dedo tem repetição natural ("ele", "era",
	 * "também" aparecem mais de uma vez na leitura) e conjunto com duplicata não carrega.
	 */
	private static Set<String> palavrasVazias() {
		return Set.copyOf(List.of("que", "para", "pra", "pro", "por", "com", "sem",
				"uma", "um", "uns", "umas", "dos", "das", "do", "da", "de", "no", "na", "nos", "nas", "ao", "aos",
				"em", "os", "as", "ele", "ela", "eles", "elas", "isso", "isto", "aquilo", "esse", "essa", "este",
				"esta", "meu", "minha", "meus", "minhas", "seu", "sua", "seus", "suas", "nosso", "nossa", "dele",
				"dela", "voc", "voce", "vc", "eu", "mim", "quem", "qual", "quais", "quando", "onde", "como",
				"porque", "muito", "muita", "muitos", "muitas", "pouco", "pouca", "mais", "menos", "bem", "mas",
				"porem", "todavia", "entao", "assim", "tambem", "sobre", "entre", "depois", "antes", "hoje",
				"ontem", "amanha", "agora", "ainda", "ja", "ser", "estar", "ter", "haver", "fazer", "ir", "vir",
				"ta", "to", "tava", "estava", "estou", "sou", "era", "vou", "vai", "vao", "foi", "sao", "tem",
				"tive", "aqui", "ali", "la", "tudo", "todo", "toda", "todos", "todas", "nada", "ninguem", "alguem",
				"cada", "qualquer", "outro", "outra", "outros", "outras", "mesmo", "mesma", "tao", "nem", "sim",
				"nao", "achei", "kkk", "kkkk", "haha", "rs",
				"bom", "boa", "dia", "noite", "tarde", "oi", "ola", "eai", "salve",
				// Sobra de endereço quando o link já saiu: nada disso nomeia conversa.
				"http", "https", "www", "ftp", "html", "htm", "php", "jsp", "utm", "href", "src", "cdn", "amp",
				"nbsp", "link", "links",
				// Chamada de conversa ("olha isso", "vejam"): o verbo não é o assunto.
				"olha", "olhem", "vejam"));
	}

	/**
	 * As expressões do texto: unigramas e bigramas de palavras elegíveis, na ordem em que
	 * aparecem, sem repetição dentro do mesmo texto (a mesma pessoa repetindo a palavra no
	 * mesmo post não infla o assunto — repete quem publica de novo, e é o chão de pessoas
	 * distintas que trava isso).
	 */
	public List<Fala> extrair(String texto, String fonte) {
		return extrair(texto, fonte, List.of());
	}

	/**
	 * As expressões do texto mais os assuntos DECLARADOS pelo autor (tags da publicação).
	 * O declarado entra como fala própria mesmo que não apareça no texto — é o autor dizendo
	 * do que a publicação trata, e é o assunto real da publicação.
	 */
	public List<Fala> extrair(String texto, String fonte, List<String> assuntosDeclarados) {
		String limpo = dobrar(sanitizar(texto));
		List<Fala> falas = new ArrayList<>();
		Map<String, Boolean> unicas = new LinkedHashMap<>();

		List<Palavra> tokens = tokenizar(limpo);
		List<String> palavras = new ArrayList<>();
		for (Palavra token : tokens) {
			if (palavraValida(token.texto())) {
				palavras.add(token.texto());
			}
		}
		for (int i = 0; i < palavras.size(); i++) {
			String atual = palavras.get(i);
			unicas.putIfAbsent(atual, true);
			// O bigrama é só de vizinhos de verdade: palavras elegíveis ADJACENTES no texto e
			// diferentes entre si — pontuação entre elas quebra o par ("meetup, bora" não cola)
			// e palavra repetida não vira par consigo mesma.
			if (i + 1 < palavras.size()) {
				String proxima = palavras.get(i + 1);
				if (!atual.equals(proxima) && vizinhas(tokens, atual, proxima)) {
					unicas.putIfAbsent(atual + " " + proxima, true);
				}
			}
		}
		for (String expressao : unicas.keySet()) {
			falas.add(new Fala(expressao, fonte));
		}
		if (assuntosDeclarados != null) {
			for (String declarado : assuntosDeclarados) {
				String expressao = normalizarDeclarado(declarado);
				if (expressao != null && unicas.putIfAbsent(expressao, true) == null) {
					falas.add(new Fala(expressao, fonte));
				}
			}
		}
		return falas;
	}

	/**
	 * As duas palavras são vizinhas no texto dobrado: entre o fim de uma e o começo da outra
	 * não pode haver nada além de espaço. É essa a diferença entre "inteligencia artificial"
	 * (um assunto) e "inteligencia, artificial" (duas palavras no mesmo texto).
	 */
	private static boolean vizinhas(List<Palavra> tokens, String primeira, String segunda) {
		for (int i = 0; i + 1 < tokens.size(); i++) {
			if (tokens.get(i).texto().equals(primeira) && tokens.get(i + 1).texto().equals(segunda)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * O clustering das falas de um texto: cada bigrama funde os seus dois unigramas no cluster
	 * dele e o assunto do cluster é o bigrama (a expressão mais longa vence). Unigrama que não
	 * entrou em bigrama nenhum é cluster próprio. É o método que decide se "ia" e
	 * "inteligencia artificial" contam juntos.
	 */
	public List<String> assuntos(List<Fala> falas) {
		Set<String> cobertas = new HashSet<>();
		Set<String> bigramas = new LinkedHashSet<>();
		for (Fala fala : falas) {
			String[] palavras = fala.expressao().split("\\s+");
			if (palavras.length > 1) {
				bigramas.add(fala.expressao());
				cobertas.add(palavras[0]);
				cobertas.add(palavras[1]);
			}
		}
		Set<String> assuntos = new LinkedHashSet<>(bigramas);
		for (Fala fala : falas) {
			if (!cobertas.contains(fala.expressao())) {
				assuntos.add(fala.expressao());
			}
		}
		return List.copyOf(assuntos);
	}

	/**
	 * A régua da LEITURA: o mesmo critério da extração vale para o assunto que já está gravado.
	 * Ruído antigo ("http", "kkkk", resto de URL) sai do ranking na leitura, sem esperar a
	 * janela de 7 dias expirar; assim a correção do extrator também limpa o que já está na base.
	 */
	public boolean assuntoValido(String assunto) {
		if (assunto == null || assunto.isBlank()) {
			return false;
		}
		String[] palavras = dobrar(assunto).trim().split("\\s+");
		if (palavras.length == 0 || palavras.length > MAXIMO_PALAVRAS_DECLARADO) {
			return false;
		}
		for (String palavra : palavras) {
			if (!palavraValida(palavra)) {
				return false;
			}
		}
		return true;
	}

	/** As palavras do texto dobradas, sem as vazias e com o tamanho mínimo. */
	static List<String> palavrasElegiveis(String texto) {
		List<String> elegiveis = new ArrayList<>();
		for (Palavra token : tokenizar(dobrar(sanitizar(texto)))) {
			if (palavraValida(token.texto())) {
				elegiveis.add(token.texto());
			}
		}
		return List.copyOf(elegiveis);
	}

	/** A palavra pode nomear conversa? (tamanho, stopword, número puro e riso ficam fora.) */
	static boolean palavraValida(String palavra) {
		if (palavra.length() < TAMANHO_MINIMO_PALAVRA || palavra.length() > TAMANHO_MAXIMO_PALAVRA) {
			return false;
		}
		if (PALAVRAS_VAZIAS.contains(palavra)) {
			return false;
		}
		if (palavra.chars().allMatch(Character::isDigit)) {
			return false;
		}
		return !RUISO.matcher(palavra).matches();
	}

	/**
	 * O assunto declarado (tag do autor) normalizado como as expressões do texto: dobrado, sem
	 * ruído, com no máximo {@link #MAXIMO_PALAVRAS_DECLARADO} palavras. Nulo quando não sobra
	 * nada que nomeie conversa (tag de link, de riso, vazia).
	 */
	static String normalizarDeclarado(String declarado) {
		if (declarado == null || declarado.isBlank()) {
			return null;
		}
		String limpo = dobrar(sanitizar(declarado)).trim();
		if (limpo.isEmpty()) {
			return null;
		}
		List<String> palavras = new ArrayList<>();
		for (String palavra : limpo.split("\\s+")) {
			if (!palavraValida(palavra)) {
				return null;
			}
			palavras.add(palavra);
		}
		if (palavras.isEmpty() || palavras.size() > MAXIMO_PALAVRAS_DECLARADO) {
			return null;
		}
		String expressao = String.join(" ", palavras);
		return expressao.length() <= TAMANHO_MAXIMO_DECLARADO ? expressao : null;
	}

	/**
	 * O texto SEM o que nunca foi conversa: link (inclusive markdown, e-mail e domínio colado
	 * sem protocolo), menção, emote de chat e sobra de HTML. O que fica é o que alguém
	 * escreveu de verdade.
	 */
	static String sanitizar(String texto) {
		if (texto == null || texto.isBlank()) {
			return "";
		}
		String semMarkdown = LINK_MARKDOWN.matcher(texto).replaceAll("$1");
		String semTag = TAG_HTML.matcher(semMarkdown).replaceAll(" ");
		String semEmail = EMAIL.matcher(semTag).replaceAll(" ");
		String semLink = LINK.matcher(semEmail).replaceAll(" ");
		String semDominio = DOMINIO.matcher(semLink).replaceAll(" ");
		String semMencao = MENCAO.matcher(semDominio).replaceAll(" ");
		return EMOTE.matcher(semMencao).replaceAll(" ");
	}

	/** As palavras com a posição no texto dobrado (a posição é o que prova a vizinhança). */
	private static List<Palavra> tokenizar(String texto) {
		List<Palavra> tokens = new ArrayList<>();
		Matcher matcher = TOKEN.matcher(texto);
		while (matcher.find()) {
			tokens.add(new Palavra(matcher.group(), matcher.start(), matcher.end()));
		}
		return tokens;
	}

	/** Minúsculas, sem acento (NFD e remoção das marcas), igual ao dobrar do Termômetro. */
	static String dobrar(String texto) {
		String minusc = texto.toLowerCase();
		String normalizada = Normalizer.normalize(minusc, Normalizer.Form.NFD);
		return normalizada.replaceAll("\\p{M}+", "");
	}

}
