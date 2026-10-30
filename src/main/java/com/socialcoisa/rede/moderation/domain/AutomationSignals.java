package com.socialcoisa.rede.moderation.domain;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/**
 * Marcadores determinísticos de que um texto pode ter saído de uma ferramenta de IA/robô.
 *
 * <p>São só o gatilho BARATO: quando nenhum marcador aparece, o post nem chega ao modelo (o que
 * manteria o custo da barreira alto demais para uma rede inteira). Quando algum aparece, quem
 * decide é o classificador — nenhuma dessas frases, sozinha, esconde um post. Por isso a lista não
 * tenta ser exaustiva: texto de máquina costuma ter, literalmente, o rastro do gerador.</p>
 */
public final class AutomationSignals {

	/** Frases que só aparecem quando alguém cola a saída de um assistente. */
	private static final List<String> DECLARACOES = List.of(
		"como um modelo de linguagem", "como uma ia", "sou uma ia", "sou um modelo de linguagem",
		"como assistente", "como uma inteligencia artificial", "enquanto ia",
		"as an ai language model", "i am an ai", "i'm an ai", "como modelo de linguagem",
		"nao tenho acesso a informacoes", "nao tenho acesso a dados", "meu treinamento",
		"fui treinado", "nao tenho sentimentos", "nao possuo sentimentos");

	/** Postura de assistente: abertura e fechamento típicos de resposta a prompt. */
	private static final List<String> POSTURA = List.of(
		"claro! aqui esta", "claro, aqui esta", "certamente! aqui", "aqui esta o texto",
		"aqui esta a versao", "aqui estao os", "espero que isso ajude", "espero ter ajudado",
		"espero que ajude", "se precisar de mais alguma coisa", "posso ajudar com mais alguma",
		"posso te ajudar com mais", "sinta-se a vontade para perguntar", "fico a disposicao para",
		"here is the", "hope this helps", "let me know if you", "feel free to ask",
		"em resumo,", "em conclusao,", "resumindo os pontos", "pontos principais:",
		"principais pontos:", "conclusao:", "resumo executivo");

	/** Teto de tamanho considerado para leitura estrutural: post maior que isso não é conversa. */
	private static final int STRUCTURE_MIN_LENGTH = 420;

	private AutomationSignals() {
	}

	/**
	 * Vale a pena mandar este texto ao classificador? Falso é "não há rastro de máquina aqui" —
	 * e aí o post é registrado como humano sem custo de modelo.
	 */
	public static boolean suspect(String content) {
		if (content == null || content.isBlank()) {
			return false;
		}
		String texto = normalize(content);
		for (String marcador : DECLARACOES) {
			if (texto.contains(marcador)) {
				return true;
			}
		}
		for (String marcador : POSTURA) {
			if (texto.contains(marcador)) {
				return true;
			}
		}
		return looksStructured(content, texto);
	}

	/**
	 * Estrutura de saída de gerador: post longo, com muitos títulos e listas, que ninguém escreve
	 * numa rede social do jeito que um modelo formata. Marcador fraco de propósito — só leva ao
	 * classificador, nunca à quarentena sozinho.
	 */
	private static boolean looksStructured(String original, String normalizado) {
		if (original.length() < STRUCTURE_MIN_LENGTH) {
			return false;
		}
		int titulos = contar(original, "##") + contar(original, "**") / 2;
		int itens = contar(normalizado, "\n- ") + contar(normalizado, "\n* ");
		return titulos >= 2 && itens >= 3;
	}

	private static int contar(String texto, String agulha) {
		int total = 0;
		int indice = texto.indexOf(agulha);
		while (indice >= 0) {
			total++;
			indice = texto.indexOf(agulha, indice + agulha.length());
		}
		return total;
	}

	/** Minúsculas e sem acento: "Aqui está" e "aqui esta" são o mesmo marcador. */
	static String normalize(String value) {
		String semAcento = Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
		return semAcento.toLowerCase(Locale.ROOT);
	}
}