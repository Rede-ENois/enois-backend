package com.socialcoisa.rede.network.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.network.application.port.out.ContentVersionRepository;
import com.socialcoisa.rede.shared.audit.AuditTrail;

/**
 * Grava o desfecho de uma conferência de conteúdo e audita a <b>transição</b>.
 *
 * <p>Existe separado porque há dois caminhos que conferem o mesmo snapshot: o nó, quando chama
 * {@code POST /network/content/reconcile}, e a varredura periódica do Control Plane. Se cada um
 * gravasse do seu jeito, o painel teria duas verdades sobre o mesmo nó — e a auditoria, dois
 * formatos.</p>
 *
 * <p>A auditoria sai só quando o motivo muda: em dia para divergente ({@code SYNC_DIVERGENCE}) ou
 * divergente para em dia ({@code SYNC_RECOVERED}). Auditar toda rodada encheria a trilha — um nó
 * parado por uma hora geraria dezenas de linhas idênticas e esconderia o que importa. O estado
 * vigente fica na linha do snapshot, que é o que o painel lê.</p>
 */
@Component
class ContentDivergenceRecorder {

	private final ContentVersionRepository versions;
	private final AuditTrail audit;
	private final Clock clock;

	ContentDivergenceRecorder(ContentVersionRepository versions, AuditTrail audit, Clock clock) {
		this.versions = versions;
		this.audit = audit;
		this.clock = clock;
	}

	/**
	 * @param newReason motivo encontrado agora; nulo = em dia
	 * @param sourceVersion versão vigente da origem, quando conhecida
	 * @return true se esta chamada foi a que gravou
	 */
	@Transactional
	public boolean record(ContentVersionRepository.NodeSnapshot snapshot, String newReason, Long sourceVersion) {
		Instant now = clock.instant();
		String previous = snapshot.divergenceReason();
		// A hora da divergência é preservada enquanto o motivo não muda: o painel mostra "divergente
		// desde", não "divergente desde a última rodada".
		Instant divergenceAt = newReason == null ? null
			: (Objects.equals(previous, newReason) && snapshot.divergenceAt() != null ? snapshot.divergenceAt() : now);
		// A escrita é condicionada ao motivo anterior: duas instâncias conferindo ao mesmo tempo leem
		// o mesmo snapshot, mas só a que encontrar a linha no motivo esperado grava e audita.
		if (versions.markChecked(snapshot.nodeId(), snapshot.kind(), snapshot.contentId(), previous, newReason,
				divergenceAt, now) != 1) {
			return false;
		}
		if (Objects.equals(previous, newReason)) {
			return true;
		}
		if (newReason == null) {
			audit.record(snapshot.nodeId(), "SYNC_RECOVERED", snapshot.contentId(),
				"{\"kind\":\"" + snapshot.kind().name() + "\",\"previousReason\":\"" + previous + "\"}");
			return true;
		}
		audit.record(snapshot.nodeId(), "SYNC_DIVERGENCE", snapshot.contentId(),
			"{\"kind\":\"" + snapshot.kind().name() + "\",\"reason\":\"" + newReason + "\",\"nodeVersion\":"
				+ snapshot.sourceVersion() + (sourceVersion == null ? "" : ",\"sourceVersion\":" + sourceVersion)
				+ "}");
		return true;
	}
}
