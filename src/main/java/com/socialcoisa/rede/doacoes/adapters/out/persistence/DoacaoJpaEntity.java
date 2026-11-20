package com.socialcoisa.rede.doacoes.adapters.out.persistence;

import java.time.Instant;
import java.util.List;

import com.socialcoisa.rede.doacoes.domain.Doacao;
import com.socialcoisa.rede.doacoes.domain.DoacaoStatus;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

/**
 * A linha da doação. Do comprovante só fica o RESULTADO da leitura: os identificadores de pix que
 * já confirmaram alguma doação (tabela doacoes_pix_ids). O texto do comprovante nunca é gravado.
 */
@Entity
@Table(name = "doacoes")
class DoacaoJpaEntity {

	@Id
	private String id;

	@Column(name = "user_id", nullable = false)
	private String userId;

	@Column(nullable = false, length = 25)
	private String txid;

	@Column(name = "amount_cents", nullable = false)
	private long amountCents;

	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "doacoes_pix_ids", joinColumns = @JoinColumn(name = "doacao_id"))
	@Column(name = "pix_id", nullable = false, length = 40)
	private List<String> pixIds;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private DoacaoStatus status;

	@Enumerated(EnumType.STRING)
	@Column(length = 16)
	private DoacaoStatus.Origem origem;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "confirmed_at")
	private Instant confirmedAt;

	@Column(name = "decided_by")
	private String decidedBy;

	static DoacaoJpaEntity from(Doacao d) {
		DoacaoJpaEntity e = new DoacaoJpaEntity();
		e.id = d.id();
		e.userId = d.userId();
		e.txid = d.txid();
		e.amountCents = d.amountCents();
		e.pixIds = d.e2eIds();
		e.status = d.status();
		e.origem = d.origem();
		e.createdAt = d.createdAt();
		e.confirmedAt = d.confirmedAt();
		e.decidedBy = d.decidedBy();
		return e;
	}

	Doacao toDomain() {
		return new Doacao(id, userId, txid, amountCents, pixIds == null ? List.of() : List.copyOf(pixIds), status,
			origem, createdAt, confirmedAt, decidedBy);
	}
}
