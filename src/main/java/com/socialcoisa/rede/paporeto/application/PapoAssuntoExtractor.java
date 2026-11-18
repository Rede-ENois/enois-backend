package com.socialcoisa.rede.paporeto.application;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * O extrator de assuntos do Papo Reto: transforma um texto público em expressões que nomeiam
 * a conversa — SEM rede, SEM modelo, decidido localmente.
 *
 * Regras do jogo (determinístico e auditável):
 * - o texto é dobrado (NFD, sem acento, minúsculo) e quebrado em palavras, igual ao léxico do
 *   Termômetro, então "Inteligência Artificial" e "inteligencia artificial" são o MESMO assunto;
 * - palavras vazias (stopwords em português: artigos, preposições, conectivos, "muito", "tô")
 *   e tokens pequenos (menos de 3 letras) não viram assunto — é o "que", "de", "pra" saindo;
 * - o extrator usa UNIGRAMAS e BIGRAMAS de palavras elegíveis ADJACENTES no texto — pontuação
 *   ou palavra vazia no meio quebra o par ("feei o meetup" não cola "feei meetup"), e palavra
 *   repetida não vira par consigo mesma ("praia praia" é um assunto só);
 * - o assunto do texto funde o bigrama com os seus unigramas: quando "inteligencia artificial"
 *   aparece junto, ela cobre "inteligencia" e "artificial" — o mais específico nomeia a
 *   conversa; unigrama que não entrou em bigrama nenhum é assunto próprio;
 * - texto sem nenhuma palavra elegível não gera assunto nenhum (a maioria do feed é saudação
 *   ou link, e não vira "alta" nenhuma).
 */
@Component
public class PapoAssuntoExtractor {

	/** Maior expressão que um assunto pode ter (em palavras): bigrama. */
	public static final int MAXIMO_PALAVRAS = 2;

	/** Menor palavra elegível: "to", "eu", "o" não nomeiam conversa. */
	private static final int TAMANHO_MINIMO_PALAVRA = 3;

	/** Maior palavra elegível: o bagaço de 5000 letras de um post não é assunto, é ruído —
	 * e o unigrama tem de caber na coluna da fala (120), como o bigrama de 59+1+59. */
	public static final int TAMANHO_MAXIMO_PALAVRA = 59;

	/**
	 * As palavras vazias do português falado na rede: dobradas (sem acento, minúsculas).
	 * A lista é fechada a dedo — cada linha aqui é uma palavra que não nomeia assunto; não
	 * existe lista automática de negócio aqui.
	 */
	private static final Set<String> PALAVRAS_VAZIAS = palavrasVazias();

	/** A fala extraída: a expressão dobrada e a fonte de onde ela veio (POST ou COMMENT). */
	public record Fala(String expressao, String fonte) {
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
				"bom", "boa", "dia", "noite", "tarde", "oi", "ola", "eai", "salve"));
	}

	/**
	 * As expressões do texto: unigramas e bigramas de palavras elegíveis, na ordem em que
	 * aparecem, sem repetição dentro do mesmo texto (a mesma pessoa repetindo a palavra no
	 * mesmo post não infla o assunto — repete quem publica de novo, e é o chão de pessoas
	 * distintas que trava isso).
	 */
	public List<Fala> extrair(String texto, String fonte) {
		List<Fala> falas = new ArrayList<>();
		if (texto == null || texto.isBlank()) {
			return falas;
		}

		List<String> palavras = palavrasElegiveis(texto);
		Map<String, Boolean> unicas = new LinkedHashMap<>();
		for (int i = 0; i < palavras.size(); i++) {
			unicas.putIfAbsent(palavras.get(i), true);
			// O bigrama é só de vizinhos de verdade: palavras elegíveis ADJACENTES no texto e
			// diferentes entre si — pontuação entre elas quebra o par ("meetup, bora" não cola
			// "meetup bora" ao "feei") e palavra repetida não vira par consigo mesma.
			if (i + 1 < palavras.size()) {
				String par = palavras.get(i) + " " + palavras.get(i + 1);
				if (unicas.containsKey(par) || palavras.get(i).equals(palavras.get(i + 1))
						|| !adjacentesNoTexto(texto, palavras.get(i), palavras.get(i + 1))) {
					continue;
				}
				unicas.putIfAbsent(par, true);
			}
		}
		for (String expressao : unicas.keySet()) {
			falas.add(new Fala(expressao, fonte));
		}
		return falas;
	}

	/**
	 * As duas palavras são vizinhas no texto original: entre a primeira ocorrência de uma e a
	 * ocorrência seguinte da outra não pode ter nem pontuação nem palavra que ficou de fora
	 * (preposição, artigo) — vizinhança é no texto, não na lista filtrada.
	 */
	private static boolean adjacentesNoTexto(String texto, String primeira, String segunda) {
		String limpo = dobrar(texto);
		java.util.regex.Matcher palavra = java.util.regex.Pattern.compile("[\\p{L}\\p{N}]+").matcher(limpo);
		boolean viAPrimeira = false;
		while (palavra.find()) {
			String token = palavra.group();
			if (!viAPrimeira) {
				viAPrimeira = token.equals(primeira);
				continue;
			}
			if (token.equals(primeira)) {
				continue;
			}
			return token.equals(segunda);
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
		Set<String> cobertas = new java.util.HashSet<>();
		Set<String> bigramas = new java.util.LinkedHashSet<>();
		for (Fala fala : falas) {
			String[] palavras = fala.expressao().split("\\s+");
			if (palavras.length > 1) {
				bigramas.add(fala.expressao());
				cobertas.add(palavras[0]);
				cobertas.add(palavras[1]);
			}
		}
		Set<String> assuntos = new java.util.LinkedHashSet<>(bigramas);
		for (Fala fala : falas) {
			if (!cobertas.contains(fala.expressao())) {
				assuntos.add(fala.expressao());
			}
		}
		return List.copyOf(assuntos);
	}

	/** As palavras do texto dobradas, sem as vazias e com o tamanho mínimo. */
	static List<String> palavrasElegiveis(String texto) {
		String limpo = dobrar(texto).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
		if (limpo.isEmpty()) {
			return List.of();
		}
		List<String> elegiveis = new ArrayList<>();
		for (String palavra : limpo.split("\\s+")) {
			if (palavra.length() >= TAMANHO_MINIMO_PALAVRA && palavra.length() <= TAMANHO_MAXIMO_PALAVRA
					&& !PALAVRAS_VAZIAS.contains(palavra)) {
				elegiveis.add(palavra);
			}
		}
		return List.copyOf(elegiveis);
	}

	/** Minúsculas, sem acento (NFD e remoção das marcas), igual ao dobrar do Termômetro. */
	static String dobrar(String texto) {
		String minusc = texto.toLowerCase();
		String normalizada = Normalizer.normalize(minusc, Normalizer.Form.NFD);
		return normalizada.replaceAll("\\p{M}+", "");
	}

}
