package com.socialcoisa.rede.moderation.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.moderation.application.port.out.AppealRepository;
import com.socialcoisa.rede.moderation.application.port.out.ReportRepository;
import com.socialcoisa.rede.moderation.application.port.out.ReportedContentAuthorReader;
import com.socialcoisa.rede.moderation.application.port.out.SanctionRepository;
import com.socialcoisa.rede.moderation.domain.Appeal;
import com.socialcoisa.rede.moderation.domain.AppealStatus;
import com.socialcoisa.rede.moderation.domain.Report;
import com.socialcoisa.rede.moderation.domain.ReportStatus;
import com.socialcoisa.rede.moderation.domain.SanctionStatus;
import com.socialcoisa.rede.moderation.domain.SanctionType;
import com.socialcoisa.rede.moderation.domain.UserSanction;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;
import com.socialcoisa.rede.notifications.application.NotificationService;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Sancoes de moderacao: a IA (quando o veredito passa no gate de confianca/sem ambiguidade) aplica
 * RESTRICAO e o autor pode recorrer; humano aprova (libera) ou nega (ban, com sessoes revogadas).
 * A IA NUNCA bana — ban so por recurso negado, decisao humana.
 */
@Service
public class SanctionService {

	private final SanctionRepository sanctions;
	private final AppealRepository appeals;
	private final ReportRepository reports;
	private final ReportedContentAuthorReader contentAuthors;
	private final ReportedContentRemover contentRemover;
	private final NotificationService notificationService;
	private final BanCleanupService banCleanup;
	private final UserProfileRepository profiles;
	private final Clock clock;

	public SanctionService(SanctionRepository sanctions, AppealRepository appeals, ReportRepository reports,
			ReportedContentAuthorReader contentAuthors, ReportedContentRemover contentRemover,
			NotificationService notificationService, BanCleanupService banCleanup, UserProfileRepository profiles,
			Clock clock) {
		this.profiles = profiles;
		this.sanctions = sanctions;
		this.appeals = appeals;
		this.reports = reports;
		this.contentAuthors = contentAuthors;
		this.contentRemover = contentRemover;
		this.notificationService = notificationService;
		this.banCleanup = banCleanup;
		this.clock = clock;
	}

	/**
	 * Aplica a penalidade de um veredito PENALIZAR da pre-triagem: remove o conteudo (post ou
	 * comentario), restringe o autor e notifica com o caminho do recurso. So vale para alvo com autor
	 * inequivoco (post/comentario); o resto segue para revisao humana.
	 */
	@Transactional
	public boolean applyForReport(Report report, String motive) {
		if (report.status() != ReportStatus.PENDING) {
			return false;
		}
		String authorId = contentAuthors.authorOf(report.targetType().name(), report.targetId()).orElse(null);
		if (authorId == null) {
			return false; // alvo sem autor claro (perfil, vaga): revisao humana decide
		}
		contentRemover.remove(report, "ia-triagem", "IA");
		if (sanctions.findActiveByUser(authorId).isEmpty()) {
			String id = "sanc_" + UUID.randomUUID().toString().replace("-", "");
			sanctions.save(UserSanction.create(id, authorId, report.id(), motive, Instant.now(clock)));
		}
		report.autoPenalize(motive);
		reports.save(report);
		notificationService.create(authorId, "SANCAO", "sistema", "Conta restrita",
			"Sua conta foi restringida apos analise automatica de uma denuncia: " + motive
				+ ". Se foi um erro, voce pode recorrer e uma pessoa do time vai revisar.",
			"/api/penalidades", "Recorrer");
		return true;
	}

	/**
	 * Restrição DIRETA de um usuário por decisão humana (ex.: contestação de transação
	 * julgada FALSA). Sem denúncia por trás — o reportId de referência é o id do registro
	 * da decisão (disputa/apelação) para o recurso do usuário funcionar.
	 */
	@Transactional
	public UserSanction restrictUser(String userId, String referenceId, String motive) {
		if (sanctions.findActiveByUser(userId).isPresent()) {
			return sanctions.findActiveByUser(userId).orElseThrow();
		}
		String id = "sanc_" + UUID.randomUUID().toString().replace("-", "");
		UserSanction saved = sanctions.save(UserSanction.create(id, userId, referenceId, motive, Instant.now(clock)));
		notificationService.create(userId, "SANCAO", "sistema", "Conta restrita",
			"Sua conta foi restrita: " + motive + ". Se foi um erro, você pode recorrer.",
			"/api/penalidades", "Recorrer");
		return saved;
	}

	/**
	 * Banimento direto por decisao humana: salva a sancao e escala para BAN, revogando
	 * todas as sessoes (o padrao do recurso negado, sem passar pelo recurso). A suspensao
	 * com prazo (SUSPENDED) tambem revoga as sessoes na hora, mas NAO vira BAN aqui: a
	 * conversao para BAN definitivo so acontece no vencimento sem recurso.
	 */
	@Transactional
	public UserSanction banAuthor(UserSanction sanction, String authorId, String reviewerId,
			com.socialcoisa.rede.identity.application.port.out.AuthSessionRepository sessions) {
		if (sanction.type() == com.socialcoisa.rede.moderation.domain.SanctionType.SUSPENDED) {
			UserSanction saved = sanctions.save(sanction);
			sessions.revokeAllByUser(authorId, Instant.now(clock));
			return saved;
		}
		UserSanction saved = sanctions.save(sanction);
		// PERMABAN já é permanente: só o RESTRICTION vira BAN no caminho do recurso negado.
		UserSanction banned = sanction.type() == com.socialcoisa.rede.moderation.domain.SanctionType.PERMABAN
			? saved
			: sanctions.save(saved.escalateToBan());
		sessions.revokeAllByUser(authorId, Instant.now(clock));
		banCleanup.cleanup(banned.id(), authorId);
		return banned;
	}

	/** Recurso do proprio usuario: so com restricao ou suspensao ativa e sem recurso pendente. */
	@Transactional
	public Appeal appeal(String userId, String message) {
		if (!StringUtils.hasText(message) || message.trim().length() < 10) {
			throw new ApiException(400, "INVALID_APPEAL", "Explica o recurso em pelo menos 10 caracteres.");
		}
		UserSanction sanction = sanctions.findActiveByUser(userId)
			.orElseThrow(() -> new ApiException(404, "NO_ACTIVE_SANCTION", "Voce nao tem sancao ativa."));
		if (sanction.isBan()) {
			throw new ApiException(409, "SANCTION_IS_BAN", "Conta banida: o recurso dela ja foi decidido.");
		}
		if (appeals.existsPendingByUser(userId)) {
			throw new ApiException(409, "APPEAL_ALREADY_PENDING", "Voce ja tem um recurso em analise.");
		}
		String id = "appl_" + UUID.randomUUID().toString().replace("-", "");
		Appeal saved = appeals.save(Appeal.create(id, sanction.id(), userId, message.trim(), Instant.now(clock)));
		notificationService.create(userId, "RECURSO", "sistema", "Recurso recebido",
			"Seu recurso esta na fila de revisao humana. Avisamos aqui quando houver decisao.",
			"/api/penalidades", "Ver");
		return saved;
	}

	/** Recurso aprovado: restricao ou suspensao sai e a conta volta ao normal. */
	@Transactional
	public UserSanction approveAppeal(String appealId, String reviewerId) {
		Appeal appeal = pendingAppeal(appealId);
		UserSanction sanction = sanctions.findById(appeal.sanctionId())
			.orElseThrow(() -> new ApiException(404, "SANCTION_NOT_FOUND", "Sancao nao encontrada."));
		appeals.save(appeal.approve(reviewerId, Instant.now(clock)));
		UserSanction removed = sanctions.save(sanction.remove(reviewerId, Instant.now(clock)));
		// O fato que motivou a sanção deixa de existir: a denúncia vira DISMISSED para a
		// credibilidade voltar ao normal. A trilha fica no próprio report (revisor, data, nota).
		// Referências que não são denúncias (ex.: decisão de contestação) não têm o que limpar.
		if (sanction.reportId() != null && sanction.reportId().startsWith("report_")) {
			reports.findById(sanction.reportId()).ifPresent(report -> {
				report.dismiss(reviewerId, "Recurso aceito pela revisão humana");
				reports.save(report);
			});
		}
		notificationService.create(sanction.userId(), "RECURSO", reviewerId, "Recurso aceito",
			"Uma pessoa do time revisou seu recurso e a restricao foi removida. Conta liberada.",
			"/api/penalidades", "Ver");
		return removed;
	}

	/** Recurso negado: sancao SUSPENDED ou restricao vira BAN (sessoes revogadas na hora). */
	@Transactional
	public UserSanction denyAppeal(String appealId, String reviewerId,
			com.socialcoisa.rede.identity.application.port.out.AuthSessionRepository sessions) {
		Appeal appeal = pendingAppeal(appealId);
		UserSanction sanction = sanctions.findById(appeal.sanctionId())
			.orElseThrow(() -> new ApiException(404, "SANCTION_NOT_FOUND", "Sancao nao encontrada."));
		appeals.save(appeal.deny(reviewerId, Instant.now(clock)));
		// Tanto a restricao quanto a suspensao com prazo convergem para BAN no indeferimento:
		// sem recurso aceito, a conta cai no mesmo estado final e o conteudo entra na purga.
		UserSanction banned = sanctions.save(sanction.escalateToBan());
		sessions.revokeAllByUser(sanction.userId(), Instant.now(clock));
		banCleanup.cleanup(banned.id(), sanction.userId());
		notificationService.create(sanction.userId(), "RECURSO", reviewerId, "Recurso negado",
			"Uma pessoa do time revisou seu recurso e manteve a penalidade. A conta agora esta banida.",
			"/api/penalidades", "Ver");
		return banned;
	}

	/**
	 * Correção manual de uma penalidade aplicada errada por um administrador: a sanção sai (status
	 * REMOVED) e a conta volta ao normal. A denúncia que a originou vira DISMISSED para a
	 * credibilidade se recompor. Conteúdo já purgado por um ban NÃO volta — a remoção em massa é
	 * irreversível e a tela avisa antes.
	 */
	@Transactional
	public UserSanction revokeSanction(String adminId, String sanctionId, String reason) {
		String cleanReason = text(reason, "Explique por que a penalidade foi removida (pelo menos 10 caracteres).");
		UserSanction sanction = sanctions.findById(sanctionId)
			.orElseThrow(() -> new ApiException(404, "SANCTION_NOT_FOUND", "Sanção não encontrada."));
		if (!sanction.isActive()) {
			throw new ApiException(409, "SANCTION_NOT_ACTIVE", "Esta penalidade já foi removida.");
		}
		if (sanction.userId().equals(adminId)) {
			throw new ApiException(400, "CANNOT_REVOKE_OWN", "Você não pode revisar a penalidade da própria conta.");
		}
		UserSanction removed = sanctions.save(sanction.remove(adminId, Instant.now(clock)));
		dismissSourceReport(sanction, adminId, "Penalidade removida por um administrador: " + cleanReason);
		notificationService.create(sanction.userId(), "SANCAO", adminId, "Penalidade removida",
			"Uma pessoa do time revisou sua penalidade e ela foi removida. Conta liberada. Motivo: " + cleanReason,
			"/api/penalidades", "Ver");
		return removed;
	}

	/**
	 * Corrige a penalidade sem removê-la: troca o tipo e o motivo mantendo o MESMO registro (mesmo
	 * id), como o painel faz ao escalar. Se a correção fecha o acesso da conta, as sessões caem na
	 * hora; se vira ban, o conteúdo entra na purga. Rebaixar um ban libera o login de volta (o
	 * conteúdo purgado, porém, não volta).
	 */
	@Transactional
	public UserSanction editSanction(String adminId, String sanctionId, SanctionType type, String reason,
			com.socialcoisa.rede.identity.application.port.out.AuthSessionRepository sessions) {
		if (type == null) {
			throw new ApiException(400, "INVALID_SANCTION_TYPE", "Escolha o novo tipo de penalidade.");
		}
		String cleanReason = text(reason, "Explique o motivo da correção (pelo menos 10 caracteres).");
		UserSanction sanction = sanctions.findById(sanctionId)
			.orElseThrow(() -> new ApiException(404, "SANCTION_NOT_FOUND", "Sanção não encontrada."));
		if (!sanction.isActive()) {
			throw new ApiException(409, "SANCTION_NOT_ACTIVE", "Esta penalidade já foi removida.");
		}
		if (sanction.userId().equals(adminId)) {
			throw new ApiException(400, "CANNOT_EDIT_OWN", "Você não pode corrigir a penalidade da própria conta.");
		}
		UserSanction updated = sanctions.save(sanction.escalateTo(type, cleanReason));
		if (!sanction.isBlocked() && updated.isBlocked()) {
			sessions.revokeAllByUser(sanction.userId(), Instant.now(clock));
		}
		if (!sanction.isBan() && updated.isBan()) {
			banCleanup.cleanup(updated.id(), sanction.userId());
		}
		notificationService.create(sanction.userId(), "SANCAO", adminId, "Penalidade atualizada",
			"Uma pessoa do time revisou a penalidade da sua conta: " + cleanReason,
			"/api/penalidades", "Ver");
		return updated;
	}

	private void dismissSourceReport(UserSanction sanction, String reviewerId, String note) {
		// Referências que não são denúncias (ex.: decisão de contestação, `admin:...`) não têm o
		// que descartar. DISMISSED já é o estado final: não sobrescreve a nota anterior.
		if (sanction.reportId() == null || !sanction.reportId().startsWith("report_")) {
			return;
		}
		reports.findById(sanction.reportId()).ifPresent(report -> {
			if (report.status() != ReportStatus.DISMISSED) {
				report.dismiss(reviewerId, note);
				reports.save(report);
			}
		});
	}

	private static String text(String value, String message) {
		String clean = value == null ? "" : value.trim();
		if (clean.length() < 10) {
			throw new ApiException(400, "REASON_REQUIRED", message);
		}
		return clean;
	}

	private Appeal pendingAppeal(String appealId) {
		Appeal appeal = appeals.findById(appealId)
			.orElseThrow(() -> new ApiException(404, "APPEAL_NOT_FOUND", "Recurso nao encontrado."));
		if (!appeal.isPending()) {
			throw new ApiException(409, "APPEAL_ALREADY_REVIEWED", "Este recurso ja foi revisado.");
		}
		return appeal;
	}

	// Leitura para o front (penalidades do proprio usuario e fila do admin).
	@Transactional(readOnly = true)
	public Optional<UserSanction> activeSanctionOf(String userId) {
		return sanctions.findActiveByUser(userId);
	}

	@Transactional(readOnly = true)
	public List<UserSanction> sanctionsOf(String userId) {
		return sanctions.findByUser(userId, 20);
	}

	@Transactional(readOnly = true)
	public List<Appeal> appealsOf(String userId) {
		return appeals.findByUser(userId, 20);
	}

	@Transactional(readOnly = true)
	public List<Appeal> pendingAppeals(int limit) {
		return appeals.findByStatus(AppealStatus.PENDING, limit);
	}

	/** Entrada da fila do admin: a sanção e quem é o autor (o perfil pode ter sido apagado). */
	public record SanctionEntry(UserSanction sanction, String username, String name) {
	}

	/** Penalidades de toda a rede num estado, mais recentes primeiro, com o autor para a tela. */
	@Transactional(readOnly = true)
	public List<SanctionEntry> adminSanctions(SanctionStatus status, int limit) {
		List<UserSanction> rows = sanctions.findByStatus(status, limit);
		if (rows.isEmpty()) {
			return List.of();
		}
		Map<String, UserProfile> authors = profiles
			.findAllByIds(rows.stream().map(UserSanction::userId).distinct().toList())
			.stream()
			.collect(Collectors.toMap(UserProfile::id, Function.identity(), (first, second) -> first));
		return rows.stream().map(row -> {
			UserProfile author = authors.get(row.userId());
			return new SanctionEntry(row, author != null ? author.username() : null,
				author != null ? author.name() : null);
		}).toList();
	}

	@Transactional(readOnly = true)
	public boolean isRestricted(String userId) {
		return sanctions.findActiveByUser(userId).isPresent();
	}

}
