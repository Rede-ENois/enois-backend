package com.socialcoisa.rede.network.domain;

/**
 * Classe de dado que a tarefa carrega. É o que decide se a tarefa pode sair da infraestrutura
 * autorizada — não o perfil do nó, não a capacidade dele.
 *
 * <p>Regra dura (docs/rede-distribuida/03-contratos.md): tarefa com dado sensível **não sai** da
 * infraestrutura do Énóis. Um nó de terceiro, por mais confiável que seja o participante, não
 * recebe {@code PERSONAL} nem {@code INTERNAL}.</p>
 */
public enum DataClass {

	/** Conteúdo público sem dado pessoal. */
	PUBLIC(true),

	/** Objeto de mídia público (o arquivo da tarefa, nada além dele). */
	PUBLIC_MEDIA(true),

	/** Dado interno do Énóis: só infraestrutura própria. */
	INTERNAL(false),

	/** Dado pessoal: só infraestrutura própria, e só o necessário. */
	PERSONAL(false);

	private final boolean mayLeaveAuthorizedInfra;

	DataClass(boolean mayLeaveAuthorizedInfra) {
		this.mayLeaveAuthorizedInfra = mayLeaveAuthorizedInfra;
	}

	public boolean mayLeaveAuthorizedInfra() {
		return mayLeaveAuthorizedInfra;
	}
}
