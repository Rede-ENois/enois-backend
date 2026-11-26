package com.socialcoisa.rede.wrapped.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.socialcoisa.rede.wrapped.application.port.out.WrappedConta;
import com.socialcoisa.rede.wrapped.application.port.out.WrappedContasRepository;
import com.socialcoisa.rede.wrapped.application.port.out.WrappedFilaRepository;

/**
 * Constrói a fila do ano: lê todas as contas elegíveis, ordena pela credibilidade atual (desc),
 * desempata por data de criação (asc) e depois por id, e insere cada uma como PENDING com a posição
 * final. A credibilidade é a do momento da construção — o briefing proíbe recalculá-la a cada
 * iteração; ela também fica gravada em {@code prioridade} para auditoria.
 *
 * <p>A construção é idempotente por conta: a linha que já existe não é reinserida (a inserção é
 * condicional no banco), então rodar o construtor duas vezes não duplica nem reordena o que já
 * estava na fila.</p>
 */
@Component
public class WrappedFilaConstrutor {

	private static final Logger LOG = LoggerFactory.getLogger(WrappedFilaConstrutor.class);
	private static final int PAGINA = 500;

	private final WrappedContasRepository contas;
	private final WrappedFilaRepository fila;

	WrappedFilaConstrutor(WrappedContasRepository contas, WrappedFilaRepository fila) {
		this.contas = contas;
		this.fila = fila;
	}

	/** Constrói (ou completa) a fila do ano; devolve quantas contas elegíveis foram lidas agora. */
	public int construir(int ano, Instant agora) {
		// Reconstruir é idempotente: a inserção só cria quem ainda NÃO tem linha no ano. Rodar de
		// novo (dia seguinte, retomada) não duplica, não reordena e não toca em quem já está na fila
		// ou concluída. Conta criada depois da primeira construção entra na seguinte.
		List<WrappedConta> elegiveis = new ArrayList<>();
		String depoisDe = null;
		while (true) {
			List<WrappedConta> pagina = contas.pagina(depoisDe, PAGINA);
			if (pagina.isEmpty()) {
				break;
			}
			elegiveis.addAll(pagina);
			depoisDe = pagina.get(pagina.size() - 1).userId();
		}
		elegiveis.sort(Comparator.comparingInt(WrappedConta::credibilidade).reversed()
				.thenComparing(WrappedConta::criadaEm, Comparator.nullsLast(Comparator.naturalOrder()))
				.thenComparing(WrappedConta::userId));

		long posicao = 0;
		for (WrappedConta conta : elegiveis) {
			fila.inserirPendente(conta.userId(), ano, posicao++, conta.credibilidade(),
					WrappedAgregador.VERSAO_ALGORITMO, agora);
		}
		LOG.info("Fila do Énóis Wrapped {} construída: {} contas elegíveis", ano, elegiveis.size());
		return elegiveis.size();
	}
}
