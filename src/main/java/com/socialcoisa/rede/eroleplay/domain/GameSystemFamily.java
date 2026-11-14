package com.socialcoisa.rede.eroleplay.domain;

import java.util.List;

/**
 * A FAMÍLIA de um sistema de regras — o pedaço de motor que o Éroleplay conhece de cor.
 *
 * <p>Um sistema de RPG não é um dado: é um conjunto de regras, e é por isso que o Éroleplay não
 * depende de nenhum sistema em particular. O que o motor precisa saber na hora de criar uma
 * campanha é a FAMÍLIA, porque ela determina o que o motor sabe fazer por conta própria: como se
 * rola um dado, como um teste se resolve, o que é crítico.</p>
 *
 * <p>Duas famílias D20 podem ter mecânicas completamente diferentes, e um D100 não é um D20 com
 * outro número de faces. Por isso a família é o mínimo comum e o resto do sistema — fórmulas,
 * graus de sucesso, progressão — é um ADAPTER registrado por campanha (Fase 4 do roadmap).</p>
 *
 * <p>Os sistemas oficiais e comunitários (Tormenta 20, Dungeons &amp; Dragons, Ordem Paranormal,
 * Cyberpunk) NÃO entram aqui: são conteúdo protegido, e a plataforma só os distribui com
 * autorização de quem detém os direitos. O catálogo de sistemas vive no Registry, e o que este
 * enum descreve é apenas o motor genérico que o Éroleplay entrega desde o começo.</p>
 */
public enum GameSystemFamily {

	/** Sistemas de dado de 20 faces: o teste resolve em successes/falhas, com crítico em 20 e falha crítica em 1. */
	D20("D20", "Sistema de dado de 20 faces"),

	/** Sistemas percentuais: o teste compara um percentual com a dificuldade, e crítico/falha crítica são faixas de resultado. */
	D100("D100", "Sistema percentual (d100)"),

	/**
	 * Sistema do próprio mestre: a campanha define as próprias regras e o motor entrega a
	 * estrutura (ficha, dados, história) sem decidir o resultado.
	 */
	CUSTOM("Personalizado", "Sistema autoral do mestre");

	private final String label;
	private final String description;

	GameSystemFamily(String label, String description) {
		this.label = label;
		this.description = description;
	}

	public String label() {
		return label;
	}

	public String description() {
		return description;
	}

	/** As famílias que o motor genérico entrega; o Registry amplia esta lista. */
	public static List<GameSystemFamily> available() {
		return List.of(values());
	}

	/**
	 * Interpreta o nome informado na criação da campanha.
	 *
	 * <p>Nome desconhecido não vira a família padrão em silêncio: uma campanha que "@d20" não
	 * pode nem ser criada, porque o mestre escolheria um sistema que não existe.</p>
	 */
	public static GameSystemFamily of(String name) {
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("sistema de regras obrigatorio");
		}
		String normalized = name.trim().toUpperCase(java.util.Locale.ROOT);
		for (GameSystemFamily family : values()) {
			if (family.name().equals(normalized)) {
				return family;
			}
		}
		throw new IllegalArgumentException("sistema de regras desconhecido: " + name.trim());
	}
}
