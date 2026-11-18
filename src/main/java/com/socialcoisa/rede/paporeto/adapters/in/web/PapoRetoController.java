package com.socialcoisa.rede.paporeto.adapters.in.web;

import java.time.Duration;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.paporeto.application.PapoRetoService;
import com.socialcoisa.rede.paporeto.application.PapoRetoWorker;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

/**
 * O endpoint do Papo Reto: os assuntos em alta e a timeline de um assunto.
 *
 * Leitura exige sessão, como os outros espaços do Explorar. É SÓ leitura — ninguém publica
 * assunto aqui (isso seria enquete, e o Papo Reto é medido do que já é público). A resposta
 * não carrega nada de individual: subject agregado, status, pontuação, contagens do conjunto
 * e a timeline por bucket — sem autor, sem texto de publicação, sem link de quem falou.
 */
@RestController
public class PapoRetoController {

	private final PapoRetoService papoReto;

	private final CurrentUserResolver currentUserResolver;

	PapoRetoController(PapoRetoService papoReto, CurrentUserResolver currentUserResolver) {
		this.papoReto = papoReto;
		this.currentUserResolver = currentUserResolver;
	}

	/** O ranking global dos assuntos em alta. */
	@GetMapping("/papo-reto")
	List<PapoRetoService.SubjectView> ranking(@RequestHeader("X-Session-Token") String sessionToken,
			@RequestParam(required = false) String comunidade) {
		currentUserResolver.resolve(sessionToken);
		return comunidade == null ? papoReto.ranking() : papoReto.rankingDaComunidade(comunidade);
	}

	/** A timeline do assunto: a evolução das falas por bucket de hora na janela pedida. */
	@GetMapping("/papo-reto/{subject}/timeline")
	List<PapoRetoService.IntervaloView> timeline(@RequestHeader("X-Session-Token") String sessionToken,
			@PathVariable String subject, @RequestParam(required = false) String comunidade,
			@RequestParam(defaultValue = "24h") String janela) {
		currentUserResolver.resolve(sessionToken);
		String escopo = comunidade == null ? PapoRetoWorker.ESCOPO_GLOBAL : comunidade;
		return papoReto.timeline(escopo, subject, janelaDaQuery(janela));
	}

	/** Quantas falas a última janela analisou (a contagem que a página mostra). */
	@GetMapping("/papo-reto/analise")
	long falasAnalisadas(@RequestHeader("X-Session-Token") String sessionToken) {
		currentUserResolver.resolve(sessionToken);
		return papoReto.falasAnalisadas();
	}

	private Duration janelaDaQuery(String janela) {
		return switch (janela) {
			case "7d" -> Duration.ofDays(7);
			case "30d" -> Duration.ofDays(30);
			default -> Duration.ofHours(24);
		};
	}

}
