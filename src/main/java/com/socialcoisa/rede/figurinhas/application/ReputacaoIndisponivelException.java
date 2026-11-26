package com.socialcoisa.rede.figurinhas.application;

import com.socialcoisa.rede.shared.web.ApiException;

/**
 * A conta não tem reputação a exibir porque a identidade não está verificada — o cálculo só conta
 * com a verificação feita, e "0 de reputação" seria uma resposta errada para quem tem histórico.
 */
public class ReputacaoIndisponivelException extends ApiException {

	public static final String CODIGO = "REPUTACAO_INDISPONIVEL";

	public ReputacaoIndisponivelException(int reputacaoMinima) {
		super(403, CODIGO, "Para enviar figurinhas você precisa ter a identidade verificada e pelo menos "
				+ reputacaoMinima + " de reputação. Verifique sua identidade para liberar o envio.");
	}
}
