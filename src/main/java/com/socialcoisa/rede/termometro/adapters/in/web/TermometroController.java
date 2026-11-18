package com.socialcoisa.rede.termometro.adapters.in.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;
import com.socialcoisa.rede.termometro.application.TermometroService;

/**
 * O endpoint do Termômetro: o painel do clima coletivo.
 *
 * Leitura exige sessão, como os outros espaços do Explorar. É SÓ leitura — o Termômetro não tem
 * escrita de quem vê (nada de publicar "como eu me sinto" aqui: isso é enquete, e o Termômetro
 * é medido, não perguntado). A resposta não carrega nada de individual: porcentagens por emoção,
 * total de manifestações, participantes distintos e o sinal de amostra pequena.
 */
@RestController
public class TermometroController {

	private final TermometroService termometro;

	private final CurrentUserResolver currentUserResolver;

	TermometroController(TermometroService termometro, CurrentUserResolver currentUserResolver) {
		this.termometro = termometro;
		this.currentUserResolver = currentUserResolver;
	}

	@GetMapping("/termometro")
	List<TermometroService.JanelaPainel> painel(@RequestHeader("X-Session-Token") String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return termometro.painel();
	}

}
