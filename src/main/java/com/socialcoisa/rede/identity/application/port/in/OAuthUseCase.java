package com.socialcoisa.rede.identity.application.port.in;

import java.util.List;

import com.socialcoisa.rede.identity.application.AuthResponse;
import com.socialcoisa.rede.identity.domain.OAuthProvider;

public interface OAuthUseCase {

	/**
	 * Inicia o login: registra state + nonce no servidor e devolve a URL do provedor.
	 *
	 * @param siteDomain dominio (principal ou espelho) de onde o fluxo partiu; desconhecido vira o principal
	 */
	OAuthStartResponse start(OAuthProvider provider, String siteDomain);

	/**
	 * Inicia a vinculacao de um provedor a conta autenticada. O callback que usar este state vincula
	 * em vez de entrar.
	 */
	OAuthStartResponse startLink(String userId, OAuthProvider provider, String siteDomain);

	/** Vinculo com escopo de escrita (GitHub `repo`), usado por quem responde issues/revisa PRs. */
	OAuthStartResponse startLinkWithWrite(String userId, OAuthProvider provider, String siteDomain);

	/**
	 * Conclui o fluxo iniciado em {@link #start}/{@link #startLink}.
	 *
	 * @param appleUserPayload JSON `user` que a Apple envia no POST do callback (opcional; so vem na
	 *                         primeira autorizacao e e a unica fonte do nome)
	 */
	AuthResponse callback(OAuthProvider provider, String code, String state, String appleUserPayload);

	/** Dominio onde o fluxo do {@code state} comecou, sem consumi-lo: o retorno ao app volta para ele. */
	String siteDomainOf(String state);

	/**
	 * Login por id_token obtido no cliente. Exige o `state` devolvido por {@link #start}: sem ele nao
	 * ha como amarrar o id_token a um pedido nosso (nonce) nem evitar reuso.
	 */
	AuthResponse tokenLogin(OAuthProvider provider, String idToken, String state);

	/** Provedores vinculados a conta (para a tela de vinculos). */
	List<LinkedProviderView> linkedProviders(String userId);

	/** Desvincula, recusando quando seria o ultimo jeito de entrar na conta. */
	void unlink(String userId, OAuthProvider provider);
}
