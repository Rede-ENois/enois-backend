package com.socialcoisa.rede.moderation.application.port.out;

import java.util.Optional;

/**
 * Conteudo e dono de uma pagina empresarial denunciada. Implementado pelo modulo de empresas.
 *
 * Pagina nao tem "autor de post": o que a triagem precisa ver e o que a pagina diz sobre si
 * (nome, setor, descricao, site) e quem responde por ela e o dono da pagina — nao a conta que
 * publicou uma mensagem.
 */
public interface CompanyReportReader {

	/** Texto legivel da pagina; vazio quando a pagina nao existe (ou ja foi removida). */
	Optional<String> contentOf(String companyId);

	/** Dono da pagina (quem recebe aviso e responde por ela); vazio quando nao ha pagina. */
	Optional<String> ownerOf(String companyId);
}
