package com.socialcoisa.rede.termometro.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.termometro.domain.CorteTermico;
import com.socialcoisa.rede.termometro.domain.TermometroPeriodo;

/**
 * Onde as fatias e os cortes do Termômetro dormem.
 *
 * A fatia é a CONTABILIDADE do corte incremental: cada manifestação classificada vira uma linha
 * com (bucket da hora de publicação, fonte, emoção, autor, id da publicação, momento da leitura).
 * O id da publicação é a chave NATURAL: gravar a mesma publicação de novo ATUALIZA a fatia em vez
 * de criar outra — o corte pode reler o horizonte sem inflar a contagem. O `lidaEm` guarda a
 * leitura mais recente que viu a publicação. Os cortes agregados são REESCRITOS a cada corte (uma
 * linha por janela), sempre a partir das fatias — nunca relendo as fontes.
 */
public interface TermometroRepository {

	/**
	 * Grava as manifestações classificadas do corte (uma linha por publicação), atualizando a
	 * fatia que já existir para o mesmo (fonte, id da publicação).
	 */
	void salvarFatias(List<Fatia> fatias);

	/** As fatias cujo bucket de publicação cai em [de, ate) — a matéria-prima do corte. */
	List<Fatia> fatiasEntre(Instant de, Instant ate);

	/**
	 * O `lidaEm` da fatia da publicação, se ela já foi contada: é o que permite o recomeço do
	 * horizonte reler tudo sem refazer o trabalho das publicações lidas na janela corrente.
	 */
	Optional<Instant> leituraDaFatia(String fonte, String publicacaoId);

	/** Sobrescreve o corte agregado da janela. */
	void salvarCorte(CorteTermico corte);

	/**
	 * Reconcilia com o que a leitura de AGORA releu (`lidas`, só as relidas — a publicação pulada
	 * por já estar contada e a que a consulta não alcança ficam fora): a publicação relida SEM
	 * texto perdeu o que contar (editada para neutro, apagada ou desativada e devolvida com o texto
	 * vazio) e a fatia dela no intervalo sai. A que só sumiu da consulta não é contradição: pode
	 * ser remoção do banco ou expiração, e a fatia expira junto com a janela. As fatias fora do
	 * intervalo ficam — ali, sumir da fonte pode ser expiração, não remoção.
	 */
	void removerFatiasDaJanela(Instant de, Instant ate, List<ManifestacaoReader.Manifestacao> lidas);

	/** O corte agregado atual da janela; vazio quando o worker ainda não passou por ela. */
	Optional<CorteTermico> corteDe(TermometroPeriodo periodo);

	/**
	 * A fatia crua do Termômetro: emoção por bucket, com o autor só para a contagem distinta. O
	 * `publicacaoId` é a chave natural (a publicação pode ter sido apagada depois: a fatia não
	 * conhece o texto, só o que o léxico classificou quando ela ainda estava no ar).
	 */
	record Fatia(Instant hora, String fonte, String emocao, String autorId, String publicacaoId, Instant lidaEm) {
	}

}
