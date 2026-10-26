package com.socialcoisa.rede.shared.crypto;

/**
 * Falha de criptografia com motivo legível — nunca "deu erro".
 *
 * <p>Os códigos existem para separar o que a pessoa que opera precisa fazer: {@code KEY_UNKNOWN} e
 * {@code KEY_INVALID} são configuração; {@code INTEGRITY_FAILED} é dado corrompido ou envelope movido
 * de lugar; {@code MALFORMED_ENVELOPE} é formato. Um 500 genérico esconde qual dos três é.</p>
 */
public class CryptoException extends RuntimeException {

	/** Código estável, para log e para o painel. */
	private final String code;

	public CryptoException(String code, String message) {
		super(message);
		this.code = code;
	}

	public CryptoException(String code, String message, Throwable cause) {
		super(message, cause);
		this.code = code;
	}

	public String code() {
		return code;
	}
}
