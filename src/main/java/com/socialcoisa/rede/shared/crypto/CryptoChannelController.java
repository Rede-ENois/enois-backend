package com.socialcoisa.rede.shared.crypto;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Handshake do canal de aplicação.
 *
 * <p>É a única rota que ainda trafega dado em claro no corpo (a chave pública efêmera do cliente),
 * e por definição: é ela que cria a chave que vai cifrar todo o resto. A resposta devolve a chave
 * pública do servidor — nenhum dos dois lados manda a chave simétrica.</p>
 *
 * <p>Não depende de sessão de usuário: o canal é de transporte, não de identidade. Um cliente
 * anônimo pode abrir canal para cifrar o próprio cadastro.</p>
 */
@RestController
@RequestMapping("/crypto")
class CryptoChannelController {

	private final CryptoChannelService channel;

	CryptoChannelController(CryptoChannelService channel) {
		this.channel = channel;
	}

	/** Corpo do handshake: apenas a chave pública efêmera. */
	record HandshakeRequest(String clientPublicKey) {
	}

	@PostMapping("/session")
	CryptoChannelService.Handshake openSession(@RequestBody HandshakeRequest request) {
		return channel.accept(request == null ? null : request.clientPublicKey());
	}
}
