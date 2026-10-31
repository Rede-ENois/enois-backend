package com.socialcoisa.rede.moderation.application.port.out;

/**
 * Tira a pagina empresarial denunciada do ar quando a revisao humana confirma a denuncia.
 *
 * Remocao aqui e FECHAR a pagina (sai do diretorio, do perfil e das listagens; o historico e as
 * vagas continuam ligados a ela), nao apagar linha: apagar a pagina levaria junto projetos,
 * propostas e membros, e nao da para desfazer um engano.
 */
public interface CompanyRemovalPort {

	/** Fecha a pagina; {@code false} quando ela nao existe mais. */
	boolean remove(String companyId, String reviewerId);
}
