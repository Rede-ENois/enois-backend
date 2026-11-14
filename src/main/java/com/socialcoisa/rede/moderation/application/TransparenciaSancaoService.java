package com.socialcoisa.rede.moderation.application;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.moderation.application.port.out.AppealRepository;
import com.socialcoisa.rede.moderation.application.port.out.SanctionRepository;
import com.socialcoisa.rede.moderation.domain.SanctionType;
import com.socialcoisa.rede.moderation.domain.UserSanction;

@Service
public class TransparenciaSancaoService {
	private final SanctionRepository sancoes;
	private final AppealRepository recursos;
	private final Clock relogio;
	private final Duration graca;
	private final EvidenciaSancaoService evidencias;

	public TransparenciaSancaoService(SanctionRepository sancoes, AppealRepository recursos, Clock relogio,
			@Value("${rede.moderation.suspension.grace-days:7}") int diasGraca, EvidenciaSancaoService evidencias) {
		this.sancoes = sancoes;
		this.recursos = recursos;
		this.relogio = relogio;
		this.graca = Duration.ofDays(Math.max(1, Math.min(diasGraca, 365)));
		this.evidencias = evidencias;
	}

	public String serverNow() {
		return relogio.instant().toString();
	}

	@Transactional(readOnly = true)
	public List<Decisao> decisoesDoTitular(String titular) {
		boolean pendente = recursos.existsPendingByUser(titular);
		return sancoes.findByUser(titular, 20).stream().map(sancao -> decisao(sancao, pendente)).toList();
	}

	private Decisao decisao(UserSanction sancao, boolean pendente) {
		Prazo prazo = sancao.type() == SanctionType.SUSPENDED
			? new Prazo("APPEAL", sancao.createdAt().plus(graca).toString()) : new Prazo("NONE", null);

		return new Decisao(sancao.id(), sancao.userId(), sancao.reportId(), sancao.type().name(),
			sancao.reason(), sancao.status().name(), sancao.createdAt().toString(),
			sancao.removedAt() == null ? null : sancao.removedAt().toString(), prazo, evidencias.daSancao(sancao),
			sancao.isActive() && !sancao.isBan() && !pendente);
	}

	public record Prazo(String kind, String at) {}
	public record Evidencia(String kind, String text) {}
	public record Decisao(String id, String userId, String reportId, String type, String reason, String status,
		String createdAt, String removedAt, Prazo deadline, Evidencia evidence, boolean canAppeal) {}
}
