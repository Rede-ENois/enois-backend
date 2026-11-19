package com.socialcoisa.rede.termometro.application;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.socialcoisa.rede.termometro.domain.Emocao;

/**
 * O léxico do Termômetro: classifica um texto público numa emoção, SEM rede, SEM modelo —
 * um conjunto de expressões em português, com janela de negação, decidido localmente.
 *
 * Regras do jogo (o que torna a classificação determinística e auditável):
 * - o texto é dobrado (NFD, sem acento, minúsculo) e quebrado em palavras antes de casar, então
 *   "não aguento" e "NAO AGUENTO" caem na mesma caixa, e "obrigada" NÃO casa com "briga" (o
 *   casamento é de palavra inteira, não de pedaço de texto);
 * - a expressão casa como sequência de palavras inteiras — "to mal ultimamente" casa como três
 *   palavras, "to mal" como duas, e as duas podem casar no mesmo texto;
 * - a negação olha as DUAS palavras anteriores ao termo ("não", "nem", "nunca", "jamais") e
 *   INVERTE o caixa quando presente — "não tenho raiva" não é PESADA;
 * - o texto sem nenhum termo fica SEM_CLASSIFICACAO e não entra em caixa nenhuma (a maioria
 *   do feed é neutra: links, memes, "bom dia" sem mais nada classificável);
 * - quando mais de uma emoção casa, vence a de maior contagem de TERMOS casados; empate vai
 *   para a ordem de prioridade declarada (precisando > pesada > cansada > celebrando > curiosa >
 *   alegre), porque pedir ajuda importa mais do que qualquer outro sinal.
 */
@Component
public class SentimentoLexicon {

	/** O veredito do léxico sobre um texto: emoção ou a ausência dela. */
	public sealed interface Veredito permits Emocional, Neutro {
	}

	public record Emocional(Emocao emocao) implements Veredito {
	}

	public record Neutro() implements Veredito {
	}

	/** Termo do léxico: a expressão dobrada e a emoção em que ela cai. */
	private record Termo(String expressao, Emocao emocao) {
	}

	/** As palavras que invertem o sentido do termo logo a seguir. */
	private static final List<String> NEGACOES = List.of("nao", "nem", "nunca", "jamais", "n");

	/** A ordem de prioridade quando duas emoções empatam em contagem de termos. */
	private static final List<Emocao> PRIORIDADE = List.of(Emocao.PRECISANDO, Emocao.PESADA,
			Emocao.CANSADA, Emocao.CELEBRANDO, Emocao.CURIOSA, Emocao.ALEGRE);

	/**
	 * O léxico em si: expressões reais de quem escreve em português, da mais específica à mais
	 * geral dentro de cada emoção. Crescer aqui é crescer o Termômetro — cada linha nova é uma
	 * forma a mais de falar que o painel passa a reconhecer.
	 */
	private static final List<Termo> TERMOS = List.of(
			// PRECISANDO — pedido de ajuda e acolhida, a prioridade máxima.
			new Termo("preciso de ajuda", Emocao.PRECISANDO), new Termo("preciso de apoio", Emocao.PRECISANDO),
			new Termo("me ajuda", Emocao.PRECISANDO), new Termo("alguem me ajuda", Emocao.PRECISANDO),
			new Termo("to mal", Emocao.PRECISANDO), new Termo("estou mal", Emocao.PRECISANDO),
			new Termo("tô mal", Emocao.PRECISANDO), new Termo("nao aguento mais", Emocao.PRECISANDO),
			new Termo("não aguento mais", Emocao.PRECISANDO), new Termo("to pra baixo", Emocao.PRECISANDO),
			new Termo("estou pra baixo", Emocao.PRECISANDO), new Termo("me sentindo sozinho", Emocao.PRECISANDO),
			new Termo("me sentindo sozinha", Emocao.PRECISANDO), new Termo("me sinto sozinho", Emocao.PRECISANDO),
			new Termo("me sinto sozinha", Emocao.PRECISANDO), new Termo("desabafar", Emocao.PRECISANDO),
			new Termo("ta dificil", Emocao.PRECISANDO), new Termo("tá difícil", Emocao.PRECISANDO),
			new Termo("esta dificil", Emocao.PRECISANDO), new Termo("está difícil", Emocao.PRECISANDO),
			new Termo("to mal ultimamente", Emocao.PRECISANDO), new Termo("to passando por uma fase", Emocao.PRECISANDO),

			// CANSADA — desgaste e esgotamento, sem ser pedido direto de ajuda.
			new Termo("cansada", Emocao.CANSADA), new Termo("cansado", Emocao.CANSADA),
			new Termo("exausta", Emocao.CANSADA), new Termo("exausto", Emocao.CANSADA),
			new Termo("esgotada", Emocao.CANSADA), new Termo("esgotado", Emocao.CANSADA),
			new Termo("sem energia", Emocao.CANSADA), new Termo("sem forcas", Emocao.CANSADA),
			new Termo("sem forças", Emocao.CANSADA), new Termo("muito sono", Emocao.CANSADA),
			new Termo("burnout", Emocao.CANSADA), new Termo("nao aguento", Emocao.CANSADA),
			new Termo("não aguento", Emocao.CANSADA), new Termo("fim de bateria", Emocao.CANSADA),

			// PESADA — raiva, injustiça, briga e tensão.
			new Termo("raiva", Emocao.PESADA), new Termo("odio", Emocao.PESADA), new Termo("ódio", Emocao.PESADA),
			new Termo("injustica", Emocao.PESADA), new Termo("injustiça", Emocao.PESADA),
			new Termo("briga", Emocao.PESADA), new Termo("briguei", Emocao.PESADA),
			new Termo("discussao", Emocao.PESADA), new Termo("discussão", Emocao.PESADA),
			new Termo("que raiva", Emocao.PESADA), new Termo("puts", Emocao.PESADA),
			new Termo("que nojo", Emocao.PESADA), new Termo("revoltante", Emocao.PESADA),
			new Termo("revoltada", Emocao.PESADA), new Termo("revoltado", Emocao.PESADA),
			new Termo("insuportavel", Emocao.PESADA), new Termo("insuportável", Emocao.PESADA),

			// CELEBRANDO — conquista e festa.
			new Termo("consegui", Emocao.CELEBRANDO), new Termo("passei", Emocao.CELEBRANDO),
			new Termo("aprovado", Emocao.CELEBRANDO), new Termo("aprovada", Emocao.CELEBRANDO),
			new Termo("contratado", Emocao.CELEBRANDO), new Termo("contratada", Emocao.CELEBRANDO),
			new Termo("comemorar", Emocao.CELEBRANDO), new Termo("parabens", Emocao.CELEBRANDO),
			new Termo("parabéns", Emocao.CELEBRANDO), new Termo("arrasei", Emocao.CELEBRANDO),
			new Termo("de boa na lagoa", Emocao.CELEBRANDO), new Termo("deu certo", Emocao.CELEBRANDO),
			new Termo("primeira vez que", Emocao.CELEBRANDO), new Termo("finally", Emocao.CELEBRANDO),
			new Termo("funcionou", Emocao.CELEBRANDO), new Termo("boraaa", Emocao.CELEBRANDO),

			// CURIOSA — pergunta e vontade de aprender.
			new Termo("como faz", Emocao.CURIOSA), new Termo("como faco", Emocao.CURIOSA),
			new Termo("como faço", Emocao.CURIOSA), new Termo("alguem sabe", Emocao.CURIOSA),
			new Termo("alguém sabe", Emocao.CURIOSA), new Termo("alguma dica", Emocao.CURIOSA),
			new Termo("duvida", Emocao.CURIOSA), new Termo("dúvida", Emocao.CURIOSA),
			new Termo("recomendam", Emocao.CURIOSA), new Termo("quem ja usou", Emocao.CURIOSA),
			new Termo("quem já usou", Emocao.CURIOSA), new Termo("o que e", Emocao.CURIOSA),
			new Termo("o que é", Emocao.CURIOSA), new Termo("quero aprender", Emocao.CURIOSA),
			new Termo("estou estudando", Emocao.CURIOSA), new Termo("comecei a estudar", Emocao.CURIOSA),

			// ALEGRE — tom leve e positivo do dia a dia.
			new Termo("feliz", Emocao.ALEGRE), new Termo("que legal", Emocao.ALEGRE),
			new Termo("adorei", Emocao.ALEGRE), new Termo("amei", Emocao.ALEGRE),
			new Termo("otimo", Emocao.ALEGRE), new Termo("ótimo", Emocao.ALEGRE),
			new Termo("maravilhoso", Emocao.ALEGRE), new Termo("maravilhosa", Emocao.ALEGRE),
			new Termo("que delicia", Emocao.ALEGRE), new Termo("que delícia", Emocao.ALEGRE),
			new Termo("bom demais", Emocao.ALEGRE), new Termo("que sonho", Emocao.ALEGRE),
			new Termo("gratidao", Emocao.ALEGRE), new Termo("gratidão", Emocao.ALEGRE),
			new Termo("meu dia fez", Emocao.ALEGRE), new Termo("sorrindo", Emocao.ALEGRE),
			new Termo("vibes", Emocao.ALEGRE));

	/**
	 * Os termos já dobrados e sem repetição: as variantes com e sem acento viram a MESMA
	 * expressão, e casar as duas contaria o mesmo sinal duas vezes (e o desempate mudaria).
	 */
	private static final List<Termo> TERMOS_DOBRADOS = dobrarTermos(TERMOS);

	private static List<Termo> dobrarTermos(List<Termo> termos) {
		Map<String, Termo> unicos = new LinkedHashMap<>();
		for (Termo termo : termos) {
			String expressao = dobrar(termo.expressao());
			unicos.putIfAbsent(expressao + "|" + termo.emocao().name(), new Termo(expressao, termo.emocao()));
		}
		return List.copyOf(unicos.values());
	}

	/**
	 * Classifica o texto: quebra em palavras, casa as expressões como sequências de palavras
	 * inteiras, respeita a negação das duas palavras anteriores e decide pela emoção com mais
	 * termos casados (com desempate por prioridade). Sem termo: neutro, e o texto não entra em
	 * nenhuma caixa.
	 */
	public Veredito classificar(String texto) {
		if (texto == null || texto.isBlank()) {
			return new Neutro();
		}

		List<String> palavras = palavrasDobradas(texto);

		Map<Emocao, Integer> termosPorEmocao = new EnumMap<>(Emocao.class);
		for (Termo termo : TERMOS_DOBRADOS) {
			if (!casa(palavras, termo.expressao())) {
				continue;
			}
			if (negado(palavras, termo.expressao())) {
				continue;
			}
			termosPorEmocao.merge(termo.emocao(), 1, Integer::sum);
		}

		Emocao vencedora = vencedora(termosPorEmocao);
		return vencedora == null ? new Neutro() : new Emocional(vencedora);
	}

	/**
	 * A emoção vencedora: mais termos casados vence; empate vai para a ordem de prioridade
	 * (a primeira da lista é a mais prioritária).
	 */
	private Emocao vencedora(Map<Emocao, Integer> termosPorEmocao) {
		Emocao vencedora = null;
		int maiorContagem = 0;
		int menorPrioridade = Integer.MAX_VALUE;
		for (Emocao emocao : Emocao.values()) {
			int contagem = termosPorEmocao.getOrDefault(emocao, 0);
			if (contagem == 0) {
				continue;
			}
			int prioridade = PRIORIDADE.indexOf(emocao);
			if (contagem > maiorContagem || (contagem == maiorContagem && prioridade < menorPrioridade)) {
				vencedora = emocao;
				maiorContagem = contagem;
				menorPrioridade = prioridade;
			}
		}
		return vencedora;
	}

	/**
	 * A expressão casa: as palavras dela aparecem, INTEIRAS e em sequência, no texto dobrado.
	 * "briga" não casa em "obrigada"; "to mal" casa em "to mal ultimamente" (e as duas expressões
	 * contam, porque são dois termos casados).
	 */
	private boolean casa(List<String> palavras, String expressao) {
		List<String> alvo = List.of(expressao.split("\\s+"));
		for (int i = 0; i + alvo.size() <= palavras.size(); i++) {
			if (palavras.subList(i, i + alvo.size()).equals(alvo)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * A expressão casou: ela está sob negação? Olha as duas palavras imediatamente anteriores à
	 * primeira palavra da expressão no texto dobrado ("nao to bem" inverte; "to bem" não).
	 */
	private boolean negado(List<String> palavras, String expressao) {
		String primeira = expressao.split("\\s+")[0];
		for (int i = 0; i < palavras.size(); i++) {
			if (!primeira.equals(palavras.get(i))) {
				continue;
			}
			for (int j = Math.max(0, i - 2); j < i; j++) {
				if (NEGACOES.contains(palavras.get(j))) {
					return true;
				}
			}
		}
		return false;
	}

	/** Minúsculas, sem acento (NFD e remoção das marcas), pronto para casar. */
	static String dobrar(String texto) {
		String minusc = texto.toLowerCase();
		String normalizada = Normalizer.normalize(minusc, Normalizer.Form.NFD);
		return normalizada.replaceAll("\\p{M}+", "");
	}

	/**
	 * O texto dobrado quebrado em palavras: a pontuação não cola uma palavra na outra, para o
	 * casamento ser de palavra inteira ("mal," e "mal" são a mesma palavra).
	 */
	static List<String> palavrasDobradas(String texto) {
		String limpo = dobrar(texto).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
		if (limpo.isEmpty()) {
			return List.of();
		}
		List<String> palavras = new ArrayList<>(List.of(limpo.split("\\s+")));
		return List.copyOf(palavras);
	}

}
