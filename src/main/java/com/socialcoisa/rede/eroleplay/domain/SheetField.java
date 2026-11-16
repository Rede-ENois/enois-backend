package com.socialcoisa.rede.eroleplay.domain;

import java.util.List;

/**
 * Um campo do modelo de ficha: o atributo do sistema mais a abertura máxima que o mestre autoriza.
 *
 * <p>É diferente de {@link SheetAttribute} de propósito. O atributo diz o que o SISTEMA pede; o
 * campo diz o que ESTA campanha aceita — inclusive a visibilidade, que é decisão de mesa, não de
 * sistema. Guardar os dois separados permite que dois mestres usem o mesmo sistema com regras de
 * segredo diferentes sem que o adapter saiba disso.</p>
 *
 * @param key identificador estável, o mesmo do atributo de origem
 * @param label rótulo legível
 * @param type como o valor é lido e validado
 * @param required se o preenchimento é obrigatório
 * @param options as opções válidas quando {@code type} é {@link AttributeType#CHOICE}
 * @param maxVisibility o máximo que o jogador pode abrir este campo (ele pode fechar mais)
 */
public record SheetField(String key, String label, AttributeType type, boolean required, List<String> options,
		SheetVisibility maxVisibility) {

	public SheetField {
		options = options == null ? List.of() : List.copyOf(options);
		if (maxVisibility == null) {
			maxVisibility = SheetVisibility.GAME_MASTER;
		}
	}

	/**
	 * O campo como o mestre o recebe de um adapter: sem opção própria de visibilidade, e com a
	 * visibilidade padrão da mesa (o mestre precisa da ficha para mestrar).
	 */
	public static SheetField from(SheetAttribute attribute) {
		return new SheetField(attribute.key(), attribute.label(), attribute.type(), attribute.required(),
			attribute.options(), SheetVisibility.GAME_MASTER);
	}

	public SheetField withVisibility(SheetVisibility visibility) {
		return new SheetField(key, label, type, required, options, visibility);
	}

	/** A restrição de comprimento de cada tipo de texto, num lugar só. */
	int lengthLimit() {
		return switch (type) {
			case TEXT -> 200;
			case LONGTEXT -> 4000;
			case NUMBER, BOOLEAN, CHOICE -> 120;
		};
	}
}