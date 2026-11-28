package com.socialcoisa.rede.doacoes.application.port.out;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.socialcoisa.rede.doacoes.domain.Doacao;
import com.socialcoisa.rede.doacoes.domain.DoacaoStatus;

public interface DoacaoRepository {

	Doacao save(Doacao doacao);

	Optional<Doacao> findById(String id);

	/** A doação aberta da pessoa, se existir (uma por vez: o QR novo aposenta o anterior). */
	Optional<Doacao> findOpenByUser(String userId);

	List<Doacao> byUser(String userId);

	List<Doacao> byStatus(DoacaoStatus status, int offset, int limit);

	long countByStatus(DoacaoStatus status);

	/** Doações confirmadas por dinheiro — o selo registrado à mão ({@code MANUAL}) não entra. */
	long countConfirmedExcludingManual();

	/** Se algum destes identificadores de pix já confirmou uma doação (para barrar comprovante repetido). */
	boolean anyPixIdUsed(Collection<String> pixIds);

	/** Quem, entre estes ids, tem doação confirmada — para carimbar o selo nas projeções. */
	Set<String> confirmedUserIds(Collection<String> userIds);
}
