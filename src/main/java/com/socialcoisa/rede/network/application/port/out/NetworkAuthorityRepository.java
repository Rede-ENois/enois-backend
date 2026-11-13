package com.socialcoisa.rede.network.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Autoridade vigente (termo) e o eleitorado declarado (V288).
 *
 * <p>As duas coisas vivem no mesmo port porque o quórum é a razão de as duas existirem: o termo diz
 * <b>qual</b> Control Plane vale, o eleitorado diz <b>quem</b> pode decidir que outro passe a valer.
 * Sem eleitorado declarado o termo não teria como subir sem que a metade de uma partição se achasse
 * maioria.</p>
 *
 * <p><b>O termo só sobe por compare-and-set</b> ({@code ... where term = esperado}). Duas promoções
 * simultâneas disputam a mesma linha: a segunda atualiza zero linhas e é recusada. É por construção
 * que dois controladores com o mesmo termo não existem — não por checagem antes de escrever, que
 * teria a janela entre a leitura e a escrita.</p>
 */
public interface NetworkAuthorityRepository {

	Optional<AuthorityRecord> current();

	/**
	 * Sobe o termo para {@code nextTerm} se a linha ainda estiver em {@code expectedTerm}.
	 *
	 * @return {@code true} quando esta chamada foi a que gravou o termo novo; {@code false} quando
	 * outra promoção gravou antes (o valor lido ficou velho) ou a linha {@code global} não existe.
	 */
	boolean promote(String authorityId, int expectedTerm, int nextTerm, Instant now);

	/** Eleitorado declarado, em ordem de declaração. */
	List<String> declared();

	/** Declara uma instância como autoridade votante. Repetir não duplica nem reescreve a data. */
	void declare(String instanceId, String actorId, Instant now);

	/** @return {@code true} se havia algo para tirar do eleitorado */
	boolean revoke(String instanceId);

	/**
	 * Instâncias do eleitorado que deram sinal depois de {@code threshold}. A vivacidade vem de
	 * {@code network_instance_readers} (V278), a mesma tabela que já responde quantas instâncias estão
	 * lendo de verdade — não de uma noção nova de "está no ar".
	 */
	Set<String> aliveSince(Instant threshold);

	/** {@code promotedAt} é nulo enquanto o termo nunca subiu: termo 0 é a autoridade original. */
	record AuthorityRecord(int term, String authorityId, Instant promotedAt) {
	}
}