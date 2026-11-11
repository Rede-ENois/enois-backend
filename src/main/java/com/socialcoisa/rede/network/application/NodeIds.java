package com.socialcoisa.rede.network.application;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Identificadores da rede: prefixo legível + entropia.
 *
 * <p>O prefixo não é decoração: ele deixa o id reconhecível em log, banco e chamada de API. A
 * entropia vem de {@link SecureRandom} e nunca de contador — um id adivinhável viraria caminho de
 * enumeração para as rotas de nó.</p>
 */
final class NodeIds {

	private static final SecureRandom RANDOM = new SecureRandom();
	private static final int BYTES = 10;

	private NodeIds() {
	}

	static String next(String prefix) {
		byte[] bytes = new byte[BYTES];
		RANDOM.nextBytes(bytes);
		return prefix + "_" + HexFormat.of().formatHex(bytes);
	}
}
