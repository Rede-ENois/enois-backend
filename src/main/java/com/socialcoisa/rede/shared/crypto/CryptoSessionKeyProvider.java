package com.socialcoisa.rede.shared.crypto;

/**
 * Adapta a chave de um canal aberto ao {@link KeyProvider}, para o {@link EnvelopeCipher} cifrar o
 * corpo de uma requisição com a chave combinada no handshake.
 *
 * <p>O {@code kid} do envelope é o identificador da sessão: se o envelope chegar com outro
 * identificador, ele não abre — o que também impede reaproveitar um corpo cifrado em outro canal.</p>
 */
public final class CryptoSessionKeyProvider implements KeyProvider {

	private final CryptoSession session;

	public CryptoSessionKeyProvider(CryptoSession session) {
		if (session == null) {
			throw new CryptoException("KEY_INVALID", "Sessao de canal obrigatoria.");
		}
		this.session = session;
	}

	@Override
	public String activeKid() {
		return session.id();
	}

	@Override
	public byte[] key(String kid) {
		if (!session.id().equals(kid)) {
			throw new CryptoException("KEY_UNKNOWN", "Envelope de outro canal.");
		}
		return session.key();
	}
}
