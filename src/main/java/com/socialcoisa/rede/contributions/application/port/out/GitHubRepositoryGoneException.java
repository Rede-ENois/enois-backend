package com.socialcoisa.rede.contributions.application.port.out;

/**
 * O repositório não existe mais no GitHub (ou virou privado): nada daquele repositório pode ser lido.
 *
 * <p>Diferente de limite ou falha passageira: insistir não resolve e o registro no repositório de
 * elegíveis continua apontando para um endereço morto. A sincronização pula o repositório e avisa a
 * administração, em vez de reprovar todos os vínculos do ciclo.</p>
 */
public class GitHubRepositoryGoneException extends RuntimeException {

	public GitHubRepositoryGoneException(String repository) {
		super("Repositório " + repository + " não existe mais no GitHub");
	}
}
