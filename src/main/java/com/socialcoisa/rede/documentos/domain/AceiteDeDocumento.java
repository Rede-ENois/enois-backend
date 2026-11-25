package com.socialcoisa.rede.documentos.domain;

import java.time.Instant;

/**
 * O aceite de uma pessoa a uma versão de documento.
 *
 * <p>Aponta para o id da VERSÃO e para o hash do texto: prova qual texto exato foi aceito, mesmo
 * que o documento evolua depois. É só registro de um ato real — o backend nunca cria um aceite
 * que a pessoa não fez.</p>
 */
public record AceiteDeDocumento(String id, String documentoId, String userId, DocumentoTipo documentoTipo,
		int documentoVersao, String conteudoHash, Origem origem, Instant aceitoEm, String ipHash, String userAgent) {

	/** De onde veio o aceite — sem isso, o histórico não distingue cadastro de aceite explícito. */
	public enum Origem {
		Cadastro,
		AceiteExplicito
	}
}
