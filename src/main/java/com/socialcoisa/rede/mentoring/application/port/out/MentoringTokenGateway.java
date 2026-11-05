package com.socialcoisa.rede.mentoring.application.port.out;

/**
 * Emite a credencial de entrada numa sala de mentoria no Epapo.
 *
 * <p>A assinatura acontece no gateway do servidor de reunião, nunca aqui: esta aplicação pede o
 * token só com os identificadores, e o gateway pergunta a ela quem pode entrar antes de assinar.
 * O navegador recebe o token pronto, nunca o segredo de assinatura.
 */
public interface MentoringTokenGateway {

	/** Traduz falhas de configuração e de rede em erro explícito para quem está na tela. */
	String tokenFor(String sessionId, String userId);

	/** Tempo de validade do token, em segundos (a tela não renova sozinha). */
	long tokenTtlSeconds();

}
