package com.socialcoisa.rede.eroleplay.domain;

import java.util.List;

/**
 * Um atributo que o sistema de regras define para a ficha.
 *
 * <p>O sistema é quem determina QUAIS campos um personagem tem; o Éroleplay não inventa atributo.
 * Este descritor é o que o mestre usa para montar o modelo de ficha (Fase 3) e o que a validação
 * do servidor confronta antes de gravar um valor.</p>
 *
 * @param key identificador estável do atributo (usado na ficha e na API)
 * @param label rótulo legível ("Força", "Classe de Armadura")
 * @param type como o valor é lido e guardado
 * @param required se o preenchimento é obrigatório
 * @param options as opções válidas, quando {@code type} é {@link AttributeType#CHOICE}
 */
public record SheetAttribute(String key, String label, AttributeType type, boolean required, List<String> options) {

	public SheetAttribute {
		options = options == null ? List.of() : List.copyOf(options);
	}

	/** Atalho para um atributo obrigatório sem opções. */
	public static SheetAttribute of(String key, String label, AttributeType type) {
		return new SheetAttribute(key, label, type, true, List.of());
	}

	/** Atalho para uma escolha obrigatória. */
	public static SheetAttribute choice(String key, String label, List<String> options) {
		return new SheetAttribute(key, label, AttributeType.CHOICE, true, options);
	}
}
