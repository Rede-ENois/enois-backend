package com.socialcoisa.rede.eroleplay.domain;

/**
 * Um valor preenchido na ficha, com a visibilidade efetiva daquele campo.
 *
 * <p>A visibilidade fica ao lado do valor, e não no modelo, porque a escolha de abrir é do
 * jogador em cada preenchimento: o mestre autoriza um máximo, a pessoa decide o que a mesa vê.
 * Guardar os dois juntos é o que impede a leitura de devolver o valor sem saber se ele podia
 * aparecer.</p>
 */
public record SheetEntry(String key, String value, SheetVisibility visibility) {

	public SheetEntry {
		if (visibility == null) {
			visibility = SheetVisibility.PRIVATE;
		}
	}

	public SheetEntry withVisibility(SheetVisibility next) {
		return new SheetEntry(key, value, next);
	}
}