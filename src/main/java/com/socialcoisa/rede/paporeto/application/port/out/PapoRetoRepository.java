package com.socialcoisa.rede.paporeto.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.paporeto.domain.PapoAssunto;
import com.socialcoisa.rede.paporeto.domain.PapoIntervalo;
import com.socialcoisa.rede.paporeto.domain.PapoTemaFala;

/**
 * Onde a contabilidade do Papo Reto dorme.
 *
 * A fala é a linha crua do corte incremental: expressão normalizada, bucket da hora de
 * publicação, fonte, autor interno (SÓ para a contagem de pessoas distintas — nunca sai para
 * a tela), comunidade de origem e a publicação que a gerou (a âncora da deduplicação). O
 * agregado do assunto é REESCRITO a cada corte e a timeline é reconciliada por bucket — o
 * mesmo bucket volta ao mesmo número.
 */
public interface PapoRetoRepository {

	/** Grava as falas novas do corte (dedup por publicação fica no worker, não aqui). */
	void salvarFalas(List<Fala> falas);

	/** A fala crua: os dois campos viram chave de dedup (publicacaoId + expressao). */
	record Fala(Instant hora, String expressao, String fonte, String autorId, String communityId,
			String publicacaoId, Instant lidaEm) {
	}

	/** As falas cujo bucket de publicação cai em [de, ate) — a matéria-prima do corte. */
	List<Fala> falasEntre(Instant de, Instant ate);

	/** As expressões já lidas de uma publicação — o corte usa para não falar duas vezes. */
	List<PapoTemaFala> temasDePublicacao(String publicacaoId);

	/** A última fronteira de leitura gravada (o maior `lidaEm`); vazio na primeira vez. */
	Optional<Instant> ultimaFronteira();

	/** Sobrescreve o agregado de um escopo: a lista inteira do escopo é reescrita a cada corte. */
	void salvarAssuntos(String escopo, List<PapoAssunto> assuntos);

	/** Todos os agregados de um escopo (o ranking lê por aqui). */
	List<PapoAssunto> assuntosDoEscopo(String escopo);

	/** O agregado atual de um subject num escopo; vazio quando o assunto não existe mais. */
	Optional<PapoAssunto> assuntoDe(String escopo, String subject);

	/** Apaga os agregados zerados de um escopo (assunto que saiu do papo). */
	void apagarAssuntosSemFala(String escopo);

	/** Reconcilia a timeline: os buckets listados voltam EXATAMENTE a estas contagens. */
	void reconciliarIntervalos(String escopo, List<PapoIntervalo> intervalos);

	/** A timeline do escopo/subject: os buckets [de, ate) em ordem do tempo. */
	List<PapoIntervalo> intervalosDe(String escopo, String subject, Instant de, Instant ate);

	/**
	 * As publicações que falaram o assunto no escopo e na janela, da mais recente para a mais
	 * antiga: a matéria-prima da visão de "publicações relacionadas". Uma publicação aparece
	 * uma vez só, mesmo que tenha falado o assunto em mais de uma expressão.
	 */
	List<PublicacaoDaFala> publicacoesDoAssunto(String escopo, String subject, Instant de, Instant ate, int limite);

	/** De onde a publicação veio e quando: o service resolve texto e autoria nas fontes. */
	record PublicacaoDaFala(String publicacaoId, String fonte, String communityId, Instant hora) {
	}

	/** Quantas falas existem na janela (observabilidade, não painel). */
	long falasTotais(Instant de, Instant ate);

}
