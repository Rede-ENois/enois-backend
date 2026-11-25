package com.socialcoisa.rede.wrapped.adapters.in.web;

import java.time.Clock;
import java.time.Instant;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.beans.factory.annotation.Value;

import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;
import com.socialcoisa.rede.wrapped.application.WrappedService;
import com.socialcoisa.rede.wrapped.domain.WrappedAno;
import com.socialcoisa.rede.wrapped.domain.WrappedRetrospectiva;

/**
 * A leitura da retrospectiva — SEMPRE da própria sessão.
 *
 * <p>A rota não recebe id de conta em lugar nenhum: o dono vem do {@code CurrentUserResolver.resolve},
 * que LANÇA sem sessão. É por construção que ninguém consegue ler a retrospectiva de outra conta —
 * trocar parâmetros no navegador ou na URL não muda de quem é o resultado, porque não há parâmetro
 * de conta. Uma conta que não está na fila do ano também não vaza nada: é uma resposta 403 explícita.</p>
 *
 * <p>Estados devolvidos: {@code READY} (resultado), {@code NOT_READY} (ainda não processada — sem
 * processamento sob demanda), {@code FORA_DA_JANELA} (fora de 26/12 a 01/01). Conteúdo sem dados
 * suficientes vem como READY com {@code dadosInsuficientes = true}.</p>
 */
@RestController
public class WrappedController {

	private final WrappedService service;
	private final CurrentUserResolver currentUserResolver;
	private final Clock clock;
	private final int ano;

	WrappedController(WrappedService service, CurrentUserResolver currentUserResolver, Clock clock,
			@Value("${rede.wrapped.ano:2026}") int ano) {
		this.service = service;
		this.currentUserResolver = currentUserResolver;
		this.clock = clock;
		this.ano = ano;
	}

	@GetMapping("/wrapped/{ano}")
	Resposta retrospectiva(@RequestHeader("X-Session-Token") String sessionToken,
			@PathVariable int ano) {
		CurrentUser usuario = currentUserResolver.resolve(sessionToken);
		if (ano != this.ano) {
			return Resposta.status(Estado.NOT_FOUND);
		}
		if (!WrappedAno.visivelEm(ano, Instant.now(clock))) {
			return Resposta.status(Estado.FORA_DA_JANELA);
		}
		String userId = usuario.id().value();
		if (!service.elegivel(userId, ano)) {
			return Resposta.status(Estado.NAO_ELEGIVEL);
		}
		return service.retrospectiva(userId, ano)
				.map(r -> new Resposta(Estado.READY, r, r.dadosInsuficientes()))
				.orElseGet(() -> Resposta.status(Estado.NOT_READY));
	}

	public enum Estado {
		READY,
		NOT_READY,
		FORA_DA_JANELA,
		NAO_ELEGIVEL,
		NOT_FOUND
	}

	public record Resposta(Estado status, WrappedRetrospectiva retrospectiva, Boolean dadosInsuficientes) {

		static Resposta status(Estado status) {
			return new Resposta(status, null, null);
		}
	}
}
