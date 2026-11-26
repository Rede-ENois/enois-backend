package com.socialcoisa.rede.paporeto.application;

import java.text.Normalizer;
import java.util.ArrayList;
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
 * - palavras vazias (stopwords em português: artigos, preposições, conectivos, pronomes, formas
 *   de verbo de ligação, "muito", "tô") e tokens pequenos (menos de 3 letras) não viram assunto
 *   — é o "que", "de", "pra" saindo; riso ("kkkk", "hahaha"), número puro ("2026") e letra
 *   repetida ("assssss") também não; a lista fecha as falas da rede que não nomeiam conversa
	 *   nenhuma ("gente", "tipo", "coisa", "sei", "tenho") — foi o bug do "gente" em alta; e
	 *   verbo no gerúndio ("fazendo", "dando", "trabalhando") é ação em curso, não assunto —
	 *   a terminação fixa da língua decide, sem lista de conjugações;
 * - o extrator usa UNIGRAMAS e EXPRESSÕES com CONTEXTO: o par é de palavras vizinhas
 *   ("inteligencia artificial", "carro quebrou") ou de palavras separadas por um conector —
 *   preposição de tema, posse ou lugar ("mercado de trabalho", "meetup na sexta", "aula de
 *   violao", "palestra sobre rust"). Pontuação, "com", "que" ou distância maior que um token
 *   quebram o par ("praia com o cachorro" não cola), e palavra repetida não vira par consigo
 *   mesma ("praia praia" é um assunto só);
 * - verbo de intenção ao lado de conector não forma par ("vou pra casa", "quero de presente"):
 *   é frase, não conversa;
 * - o pareamento é guloso e NÃO SOBREPOE: cada palavra entra em uma única expressão, da
 *   esquerda para a direita, e o par vence a palavra solta — assim "meetup de rust na sexta"
 *   vira "meetup de rust" + "sexta" (a mesma conversa não é contada duas vezes sob dois pares);
 * - o assunto do texto é o CLUSTER já decidido na extração: a palavra que entrou num par não
 *   sai solta (é o contexto que nomeia a conversa — "mercado de trabalho" e não "mercado"), e
 *   só a palavra que não entrou em par nenhum é assunto próprio;
 * - as TAGS da publicação são assunto declarado pelo autor e entram como fala própria, sem
 *   depender de aparecerem no texto; o assunto da taxonomia (TECNOLOGIA, DESIGN…) fica de
 *   fora de propósito: é amplo demais e viraria alta de todo mundo;
 * - texto sem nenhuma palavra elegível não gera assunto nenhum (a maioria do feed é saudação
 *   ou link, e não vira "alta" nenhuma).
 */
@Component
public class PapoAssuntoExtractor {

	/**
	 * Maior expressão que um assunto tirado do texto pode ter, em PALAVRAS ELEGÍVEIS: o par.
	 * O conector no meio da expressão composta ("mercado DE trabalho") não conta como palavra —
	 * ele não é o assunto, ele cola o assunto.
	 */
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
	 * existe lista automática de negócio aqui. Ela é a PRIMEIRA peneira, não a única: palavra
	 * de conversa que escapar dela cai na régua de publicações distintas do agregado
	 * ({@code PapoAssunto}), que aprende a fala comum dos números do próprio corte.
	 */
	private static final Set<String> PALAVRAS_VAZIAS = palavrasVazias();

	/**
	 * Os conectores de expressão composta: o que fica entre as duas palavras do par e as cola
	 * numa conversa só — "mercado de trabalho", "aula de violao", "meetup na sexta", "mutirao da
	 * horta", "curso pra iniciante", "palestra sobre rust". São as preposições que o português
	 * usa para dizer "sobre o quê" — a posse, o lugar e o tema. Contrações ("do", "da", "no",
	 * "na") também colam: "mutirao do bairro" é uma conversa.
	 */
	private static final Set<String> CONECTORES = Set.of("de", "do", "da", "dos", "das", "na", "no", "nas", "nos",
			"pra", "pro", "para", "sobre");

	/**
	 * Os verbos de intenção: a palavra da esquerda é verbo e a da direita vem logo depois de um
	 * conector ("vou pra casa", "quero de presente", "preciso na sexta")? Então é FRASE, não
	 * expressão composta — o sujeito que age e o lugar da ação não são a mesma conversa. É a
	 * regra por SENTIDO; a do conector é por ESCRITA.
	 */
	private static final Set<String> VERBOS = Set.of("vou", "vamos", "vai", "vao", "quero", "queria", "queremos",
			"pretendo", "pretende", "decidi", "tentei", "tentar", "consegui", "preciso", "embarquei", "participei");

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

	/**
	 * Verbo no gerúndio: a terminação é fixa na língua ("fazendo", "dando", "trabalhando",
	 * "crescendo"), então a regra é estrutural e cobre a conjugação inteira sem lista — foi o
	 * bug do "fazendo" em alta. A palavra pode colar num assunto real ("crescendo a rede" perde
	 * o verbo e fica com "rede"); verbo solto não nomeia conversa nenhuma.
	 */
	private static final Pattern GERUNDIO = Pattern.compile("(?u)^(?:.+)?(?:ando|endo|indo)$");

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
				// Contrações e plurais que faltavam: o "pelo", o "num", o "nessa" e o "voces"
				// passavam pela peneira e viravam assunto próprio.
				"pelo", "pela", "pelos", "pelas", "num", "numa", "nuns", "numas", "dum", "duma", "duas", "dois",
				"voces", "vcs", "teu", "tua", "teus", "tuas", "desse", "dessa", "desses", "dessas", "deste",
				"desta", "destes", "destas", "nesse", "nessa", "nesses", "nessas", "neste", "nesta", "nestes",
				"nestas", "disso", "nisso", "disto", "nisto", "nele", "nela", "neles", "nelas", "aquele", "aquela",
				"aqueles", "aquelas", "algum", "alguma", "alguns", "algumas", "nenhum", "nenhuma", "ate", "desde",
				"apos", "durante", "conforme", "talvez", "quase",
				// Formas de verbo de ligação, de verbo leve e de primeira pessoa: quem diz "tenho",
				// "acho", "sei" está falando de si, não de um assunto.
				"estao", "estamos", "estive", "tinha", "tinham", "temos", "havia", "seja", "sejam", "sendo",
				"sido", "pode", "podem", "podia", "deve", "devem", "queria", "quer", "querem", "quero", "posso",
				"sei", "tenho", "acho", "sabe", "sabem", "faz", "fazem", "fez", "fiz", "feito", "vamos", "vamo",
				"ver", "olha", "olhem", "vejam",
				// Fala da rede: aparece em publicação de todo mundo e não nomeia conversa nenhuma.
				// Foi a lista que faltava no bug do "gente", do "mundo" e do "enois" em alta.
				"gente", "galera", "pessoal", "mundo", "enois", "nois", "tipo", "coisa", "coisas", "negocio",
				"negocios", "bagulho", "bora", "sla", "pdc", "pdp", "vlw", "flw", "obg", "blz", "eita", "vish",
				"poxa", "caramba", "putz", "puts", "opa", "rsrs", "risos", "hahaha", "realmente", "basicamente",
				"literalmente", "sinceramente", "certamente", "praticamente",
				// Palavra genérica demais para nomear assunto (foi o bug do "cara" e do "dar"
				// em alta na produção).
				"cara", "dar", "pessoa", "pessoas", "vez", "vezes",
				// O comentário da publicação e o tempo relativo: "promocao do meu curso de ingles"
				// fala de "curso de ingles" (o assunto) e de "promocao" (o tom) — e "mutirao HOJE"
				// fala do mutirao. Fica na lista a dedo porque o par descolou a palavra do contexto
				// e a régua do agregado só alcança o que aparece em muitos textos.
				"promocao", "promocoes", "divulgacao", "novidade", "novidades", "urgente", "importante", "lembrete",
				// Sobra de endereço quando o link já saiu: nada disso nomeia conversa.
				"http", "https", "www", "ftp", "html", "htm", "php", "jsp", "utm", "href", "src", "cdn", "amp",
				"nbsp", "link", "links"));
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
		List<Integer> elegiveis = new ArrayList<>();
		for (int i = 0; i < tokens.size(); i++) {
			if (palavraValida(tokens.get(i).texto())) {
				elegiveis.add(i);
			}
		}
		for (String expressao : expressoes(tokens, elegiveis)) {
			unicas.putIfAbsent(expressao, true);
		}
		for (String expressao : assuntosDoTexto(unicas.keySet())) {
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
	 * As expressões do texto na ordem em que aparecem: o par de palavras elegíveis vizinhas e a
	 * palavra que não entrou em par nenhum (a palavra do par também entra sozinha — é o cluster
	 * de {@link #assuntos(List)} que apaga o unigrama coberto pela expressão).
	 *
	 * O pareamento é guloso, da esquerda para a direita, e NÃO SOBREPÕE: consumida a dupla, a
	 * leitura continua depois dela — "meetup de rust na sexta" vira "meetup de rust" + "sexta",
	 * e não dois pares contando a mesma conversa duas vezes.
	 */
	private static List<String> expressoes(List<Palavra> tokens, List<Integer> elegiveis) {
		List<String> expressoes = new ArrayList<>();
		int i = 0;
		while (i < elegiveis.size()) {
			String atual = tokens.get(elegiveis.get(i)).texto();
			if (i + 1 < elegiveis.size()) {
				int posicaoAtual = elegiveis.get(i);
				int posicaoProxima = elegiveis.get(i + 1);
				String proxima = tokens.get(posicaoProxima).texto();
				// O conector pode estar entre as duas OU depois da próxima ("vou pra ... casa"):
				// verbo e conector não formam par em nenhuma das duas escritas.
				boolean conectorDepoisDaProxima = posicaoProxima + 1 < tokens.size()
						&& CONECTORES.contains(tokens.get(posicaoProxima + 1).texto());
				boolean verboAntesDoConector = posicaoAtual + 1 < tokens.size()
						&& CONECTORES.contains(tokens.get(posicaoAtual + 1).texto());
				String conector = conectorEntre(tokens, posicaoAtual, posicaoProxima);
				if (conector != null && !atual.equals(proxima)
						&& !(ligaVerboATema(atual, verboAntesDoConector) || ligaVerboATema(proxima,
								conectorDepoisDaProxima))) {
					expressoes.add(atual + conector + proxima);
					// A palavra do par NÃO entra solta depois dele: é o contexto que nomeia a
					// conversa, e a palavra sozinha ("mercado") seria a mesma conversa contada de
					// novo sem o contexto que a define.
					i += 2;
					continue;
				}
			}
			expressoes.add(atual);
			i++;
		}
		return expressoes;
	}

	/**
	 * A palavra é verbo de intenção e o conector está logo ao lado dela ("vou pra casa", "quero
	 * de presente")? Então não é expressão composta: é frase — o verbo diz quem age, não sobre
	 * o que se fala.
	 */
	private static boolean ligaVerboATema(String palavra, boolean conectorAoLado) {
		return conectorAoLado && VERBOS.contains(palavra);
	}

	/**
	 * O que há entre as duas palavras: espaço quando as posições são vizinhas (o par direto),
	 * o conector cercado de espaço quando há um único token entre elas e ele é conector de
	 * expressão composta, e nulo quando nada cola — pontuação, palavra vazia que não é conector
	 * ("feei o meetup") ou distância maior que um token ("praia com o cachorro").
	 */
	private static String conectorEntre(List<Palavra> tokens, int posicaoAtual, int posicaoProxima) {
		if (posicaoProxima == posicaoAtual + 1) {
			return " ";
		}
		if (posicaoProxima == posicaoAtual + 2 && CONECTORES.contains(tokens.get(posicaoAtual + 1).texto())) {
			return " " + tokens.get(posicaoAtual + 1).texto() + " ";
		}
		return null;
	}

	/**
	 * A ordem dos assuntos de um texto: primeiro a expressão composta, depois a palavra solta,
	 * na ordem em que apareceram. Serve para quem monta as falas na mão (tag declarada, leitura
	 * de teste) na mesma ordem em que a extração as grava.
	 */
	/**
	 * O assunto da publicação, decidido na extração: só a expressão COM CONTEXTO (duas ou mais
	 * palavras, como "inteligencia artificial", "mercado de trabalho", "meetup de rust") vira
	 * assunto. A palavra solta ("rust", "praia", "trabalho") não é conversa: é vocabulário comum
	 * da rede e, sozinha, produz alta de ruído. Foi o bug das palavras soltas no ranking.
	 *
	 * <p>A expressão composta continua vindo primeiro (a ordem de {@link #expressoes} já garante
	 * isso); o filtro só mantém o que tem contexto. Se o texto não tiver nenhum par, ele não gera
	 * assunto nenhum — a publicação entra nas contagens, não na lista de assuntos.</p>
	 */
	public static List<String> assuntos(List<Fala> falas) {
		List<String> assuntos = new ArrayList<>();
		for (Fala fala : falas) {
			if (temContexto(fala.expressao())) {
				assuntos.add(fala.expressao());
			}
		}
		return List.copyOf(new LinkedHashSet<>(assuntos));
	}

	/** A expressão nomeia uma conversa com contexto? Só o composto (2+ palavras) passa. */
	public static boolean temContexto(String expressao) {
		return composta(expressao);
	}

	/**
	 * A expressão nomeia mais de uma palavra? Então é composta — é a mesma régua do domínio,
	 * que olha se o subject tem espaço: expressão de duas palavras é a conversa específica,
	 * palavra solta é a conversa de todo mundo.
	 */
	public static boolean composta(String expressao) {
		return palavrasValidas(expressao).size() > 1;
	}

	/** As palavras ELEGÍVEIS de uma expressão: o conector de expressão composta não é palavra dela. */
	private static List<String> palavrasValidas(String expressao) {
		List<String> palavras = new ArrayList<>();
		for (String palavra : expressao.trim().split("\\s+")) {
			if (palavraValida(palavra)) {
				palavras.add(palavra);
			}
		}
		return palavras;
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
		int validas = 0;
		for (int i = 0; i < palavras.length; i++) {
			if (palavraValida(palavras[i])) {
				validas++;
				continue;
			}
			// Fora das pontas, o token pode ser o conector da expressão composta
			// ("mercado de trabalho"); abrindo ou fechando o assunto, nunca.
			if (i == 0 || i == palavras.length - 1 || !CONECTORES.contains(palavras[i])) {
				return false;
			}
		}
		return validas > 0 && validas <= MAXIMO_PALAVRAS;
	}

	/**
	 * O cluster do texto, decidido na extração: a expressão composta vence e cobre as suas
	 * palavras; a palavra solta só continua assunto quando nenhum par a usou. É o mesmo critério
	 * de {@link #assuntos(List)}, aplicado cedo — o assunto que vai para a linha gravada é o que
	 * a leitura lê, e o painel não mostra "cafeteira" e "cafeteira nesse" como duas conversas.
	 */
	private static List<String> assuntosDoTexto(Set<String> expressoes) {
		List<Fala> falas = new ArrayList<>();
		for (String expressao : expressoes) {
			falas.add(new Fala(expressao, ""));
		}
		return assuntos(falas);
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
		if (GERUNDIO.matcher(palavra).matches()) {
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
