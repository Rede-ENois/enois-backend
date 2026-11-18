package com.socialcoisa.rede.paporeto.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.socialcoisa.rede.paporeto.application.port.out.PapoRetoRepository;
import com.socialcoisa.rede.paporeto.domain.PapoAssunto;
import com.socialcoisa.rede.paporeto.domain.PapoIntervalo;
import com.socialcoisa.rede.paporeto.domain.PapoStatus;

/**
 * A leitura do Papo Reto para a página: o ranking dos assuntos em alta (global e por
 * comunidade) e a timeline de um subject.
 *
 * A view não carrega nada de individual: não existe autor, não existe texto de publicação e
 * não existe quem falou o quê — o que o worker agregou é o teto do que a tela vê. O ranking
 * de um escopo ordena pela pontuação do corte mais recente; a timeline é série histórica por
 * bucket de hora. Assunto com menos gente distinta que o chão não entra na lista.
 */
@Component
public class PapoRetoService {

	/** O assunto pronto para a tela: subject, status de trajetória, pontuação e números. */
	public record SubjectView(String subject, String status, double pontuacao, long falasPost,
			long falasComentario, long pessoas, long falasUltimaHora, double crescimento) {
	}

	/** O ponto da timeline: bucket de hora e quantas falas o assunto teve ali. */
	public record IntervaloView(String hora, long falas) {
	}

	private final PapoRetoRepository repository;

	private final PapoRetoWorker worker;

	private final Clock clock;

	PapoRetoService(PapoRetoRepository repository, PapoRetoWorker worker, Clock clock) {
		this.repository = repository;
		this.worker = worker;
		this.clock = clock;
	}

	/** O ranking global: assuntos do feed geral E das comunidades, com o chão de gente. */
	public List<SubjectView> ranking() {
		return rankingDe(PapoRetoWorker.ESCOPO_GLOBAL);
	}

	/**
	 * O ranking da comunidade: o agregado do escopo da comunidade — só o que foi falado lá.
	 * O mesmo assunto pode estar nos dois rankings com números diferentes: no global ele soma
	 * toda a rede, na comunidade só a conversa dela.
	 */
	public List<SubjectView> rankingDaComunidade(String communityId) {
		return rankingDe(communityId);
	}

	private List<SubjectView> rankingDe(String escopo) {
		Instant agora = clock.instant();
		Instant antes = agora.minus(PapoAssunto.MEIA_VIDA);

		record Classificado(PapoAssunto assunto, double pontuacao, double antes) {
		}
		List<Classificado> classificados = new ArrayList<>();
		for (PapoAssunto assunto : repository.assuntosDoEscopo(escopo)) {
			if (assunto.pessoas() < PapoAssunto.MINIMO_PESSOAS) {
				continue;
			}
			double pontuacao = worker.pontuacaoDe(assunto, agora);
			double antesDaPotencia = worker.pontuacaoDe(assunto, antes);
			classificados.add(new Classificado(assunto, pontuacao, antesDaPotencia));
		}

		List<SubjectView> ranking = new ArrayList<>();
		for (Classificado classificado : classificados) {
			PapoStatus status = PapoStatus.de(classificado.pontuacao, classificado.antes);
			ranking.add(new SubjectView(classificado.assunto().subject(), status.name(), classificado.pontuacao,
					classificado.assunto().falasPost(), classificado.assunto().falasComentario(),
					classificado.assunto().pessoas(), classificado.assunto().falasUltimaHora(),
					crescimento(classificado.pontuacao, classificado.antes)));
		}
		ranking.sort((a, b) -> Double.compare(b.pontuacao(), a.pontuacao()) != 0
				? Double.compare(b.pontuacao(), a.pontuacao()) : a.subject().compareToIgnoreCase(b.subject()));
		return ranking;
	}

	/** A timeline do subject no escopo: os buckets da janela, do mais antigo ao mais recente. */
	public List<IntervaloView> timeline(String escopo, String subject, Duration janela) {
		Instant agora = clock.instant();
		Instant de = agora.minus(janela);
		List<IntervaloView> timeline = new ArrayList<>();
		for (PapoIntervalo intervalo : repository.intervalosDe(escopo, subject, de, agora)) {
			timeline.add(new IntervaloView(intervalo.hora().toString(), intervalo.falas()));
		}
		// A ordem do tempo é promessa da leitura, não do armazenamento: o service garante.
		timeline.sort(java.util.Comparator.comparing(IntervaloView::hora));
		return timeline;
	}

	/** Quantas falas a última janela de corte analisou (a contagem que a página mostra). */
	public long falasAnalisadas() {
		Instant agora = clock.instant();
		return repository.falasTotais(agora.minus(Duration.ofDays(7)), agora);
	}

	/** O crescimento relativo: quanto a pontuação subiu desde a véspera (0 quando não subiu). */
	private double crescimento(double agora, double antes) {
		if (antes <= 0) {
			return agora > 0 ? 100.0 : 0.0;
		}
		return Math.max(0, ((agora - antes) / antes) * 100.0);
	}

}
