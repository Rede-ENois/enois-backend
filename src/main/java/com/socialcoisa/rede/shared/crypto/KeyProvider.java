package com.socialcoisa.rede.shared.crypto;

/**
 * De onde vêm as chaves de dados (DEK) do envelope.
 *
 * <p>O contrato é deliberadamente pequeno: quem cifra só precisa saber <em>qual</em> chave está ativa
 * e <em>buscar</em> uma chave pelo identificador que veio no envelope. Em produção a implementação
 * desembrulha a DEK no KMS; nos testes, um mapa em memória. O material da chave nunca é gravado em
 * log nem em envelope — só o {@code kid}.</p>
 */
public interface KeyProvider {

	/** Identificador da chave que cifra registros novos. */
	String activeKid();

	/**
	 * Chave de 32 bytes (AES-256) do identificador.
	 *
	 * @throws CryptoException {@code KEY_UNKNOWN} quando o {@code kid} não existe nesta instalação
	 */
	byte[] key(String kid);
}
