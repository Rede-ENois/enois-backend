package com.socialcoisa.rede.moderation.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.identity.application.port.out.AuthSessionRepository;
import com.socialcoisa.rede.moderation.application.port.out.ModerationVoteRepository;
import com.socialcoisa.rede.moderation.application.port.out.ModeratorApplicationRepository;
import com.socialcoisa.rede.moderation.application.port.out.ReportRepository;
import com.socialcoisa.rede.moderation.application.port.out.ReportedContentAuthorReader;
import com.socialcoisa.rede.moderation.application.port.out.SanctionRepository;
import com.socialcoisa.rede.moderation.domain.ModerationVote;
import com.socialcoisa.rede.moderation.domain.ModerationVoteType;
import com.socialcoisa.rede.moderation.domain.ModeratorApplication;
import com.socialcoisa.rede.moderation.domain.ModeratorApplicationStatus;
import com.socialcoisa.rede.moderation.domain.Report;
import com.socialcoisa.rede.moderation.domain.ReportStatus;
import com.socialcoisa.rede.moderation.domain.SanctionType;
import com.socialcoisa.rede.moderation.domain.UserSanction;
import com.socialcoisa.rede.notifications.application.NotificationService;
import com.socialcoisa.rede.profile.application.ReputationService;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Candidatura a moderador e o painel de votacao das denuncias.
 *
 * Regra de entrada: credibilidade de NO MINIMO {@code rede.moderation.moderator-min-reputation}
 * (200, calibrado para a escala do modelo de componentes) e uma candidatura aprovada por admin.
 * O papel nao existe em `users` — a candidatura APPROVED vigente e a fonte da verdade; revogar
 * muda o estado e nao apaga o historico.
 *
 * Votacao: cada moderador tem um voto por denuncia (PUNISH/DISMISS). Quando um lado atinge o
 * quorum ({@code rede.moderation.vote-quorum}, 3) e esta a frente, a decisao e aplicada pelo
 * fluxo que ja existe: PUNISH usa o mesmo `penalize` do painel admin (remove o post e restringe o
 * autor); DISMISS descarta. Empate ou abaixo do quorum continua PENDING para um admin decidir.
 * O voto nunca bane — banimento continua sendo decisao humana no painel admin.
 */
@Service
public class ModeratorService {

	private final ModeratorApplicationRepository applications;
	private final ModerationVoteRepository votes;
	private final ReportRepository reports;
	private final ModerationService moderationService;
	private final SanctionService sanctionService;
	private final SanctionRepository sanctions;
	private final ReputationService reputationService;
	private final ReportedContentAuthorReader contentAuthors;
	private final NotificationService notifications;
	private final UserProfileRepository profiles;
	private final AuthSessionRepository sessions;
	private final com.socialcoisa.rede.feed.application.port.in.PurgeAuthorContentUseCase contentPurge;
	private final com.socialcoisa.rede.shared.sanctions.SanctionGateReader sanctionGate;
	private final IpBlockService ipBlockService;
	private final Clock clock;
	private final int minReputation;
	private final int voteQuorum;

	public ModeratorService(ModeratorApplicationRepository applications, ModerationVoteRepository votes,
			ReportRepository reports, ModerationService moderationService, SanctionService sanctionService,
			SanctionRepository sanctions, ReputationService reputationService, ReportedContentAuthorReader contentAuthors,
			NotificationService notifications, UserProfileRepository profiles, AuthSessionRepository sessions,
			com.socialcoisa.rede.feed.application.port.in.PurgeAuthorContentUseCase contentPurge,
			com.socialcoisa.rede.shared.sanctions.SanctionGateReader sanctionGate,
			IpBlockService ipBlockService,
			Clock clock,
			@Value("${rede.moderation.moderator-min-reputation:200}") int minReputation,
			@Value("${rede.moderation.vote-quorum:3}") int voteQuorum) {
		this.applications = applications;
		this.votes = votes;
		this.reports = reports;
		this.moderationService = moderationService;
		this.sanctionService = sanctionService;
		this.sanctions = sanctions;
		this.reputationService = reputationService;
		this.contentAuthors = contentAuthors;
		this.notifications = notifications;
		this.profiles = profiles;
		this.sessions = sessions;
		this.contentPurge = contentPurge;
		this.sanctionGate = sanctionGate;
		this.ipBlockService = ipBlockService;
		this.clock = clock;
		this.minReputation = minReputation;
		this.voteQuorum = Math.max(1, voteQuorum);
	}

	/** Punição por votação de moderadores só existe a partir deste tamanho de time. */
	public static final int MIN_MODERATORS_FOR_SANCTION = 10;

	/** Estado do usuario na tela: credibilidade, elegibilidade, papel e ultima candidatura. */
	@Transactional(readOnly = true)
	public MeView me(String userId) {
		int reputation = reputationService.reputationOf(userId);
		ModeratorApplication latest = applications.findLatestByUser(userId).orElse(null);
		boolean moderator = applications.findApprovedByUser(userId).isPresent();
		long moderatorCount = applications.countByStatus(ModeratorApplicationStatus.APPROVED);
		return new MeView(reputation, minReputation, reputation >= minReputation, moderator, moderatorCount,
			MIN_MODERATORS_FOR_SANCTION, latest == null ? null : applicationView(latest));
	}

	@Transactional
	public ApplicationView apply(String userId, String motivation, String experience, String availability) {
		String why = trim(motivation);
		if (why.length() < 20) {
			throw new ApiException(400, "INVALID_MOTIVATION",
				"Conte em pelo menos 20 caracteres por que você quer moderar a comunidade.");
		}
		if (why.length() > 2000) {
			throw new ApiException(400, "INVALID_MOTIVATION", "A motivação pode ter no máximo 2000 caracteres.");
		}
		if (applications.findApprovedByUser(userId).isPresent()) {
			throw new ApiException(409, "ALREADY_MODERATOR", "Você já é moderador.");
		}
		if (applications.existsPendingByUser(userId)) {
			throw new ApiException(409, "APPLICATION_PENDING", "Sua candidatura já está em análise.");
		}
		int reputation = reputationService.reputationOf(userId);
		if (reputation < minReputation) {
			throw new ApiException(403, "LOW_REPUTATION",
				"Para se candidatar é preciso ter pelo menos " + minReputation + " de credibilidade.");
		}
		ModeratorApplication application = ModeratorApplication.create("mapp_" + randomId(), userId, why,
			trimOrNull(experience), trimOrNull(availability), Instant.now(clock));
		ModeratorApplication saved = applications.save(application);
		notifications.create(userId, "MODERACAO", "sistema", "Candidatura recebida",
			"Recebemos sua candidatura a moderador. Avisamos aqui quando um administrador revisar.",
			"/moderador", "Acompanhar");
		return applicationView(saved);
	}

	@Transactional(readOnly = true)
	public List<ApplicationView> pendingApplications(int limit) {
		return applications.findByStatus(ModeratorApplicationStatus.PENDING, limit).stream()
			.map(this::applicationView)
			.toList();
	}

	@Transactional(readOnly = true)
	public List<ApplicationView> applicationsOfStatus(ModeratorApplicationStatus status, int limit) {
		return applications.findByStatus(status, limit).stream().map(this::applicationView).toList();
	}

	@Transactional
	public ApplicationView decideApplication(String applicationId, String reviewerId, boolean approve,
			String note) {
		ModeratorApplication application = applications.findById(applicationId)
			.orElseThrow(() -> new ApiException(404, "APPLICATION_NOT_FOUND", "Candidatura não encontrada."));
		if (!application.isPending()) {
			throw new ApiException(409, "APPLICATION_ALREADY_REVIEWED", "Esta candidatura já foi revisada.");
		}
		String cleanNote = trimOrNull(note);
		if (!approve && (cleanNote == null || cleanNote.length() < 5)) {
			throw new ApiException(400, "NOTE_REQUIRED", "Explique o motivo da recusa (pelo menos 5 caracteres).");
		}
		Instant now = Instant.now(clock);
		ModeratorApplication decided = approve
			? application.approve(reviewerId, cleanNote, now)
			: application.reject(reviewerId, cleanNote, now);
		ModeratorApplication saved = applications.save(decided);
		notifications.create(application.userId(), "MODERACAO", reviewerId,
			approve ? "Agora você é moderador" : "Candidatura a moderador recusada",
			approve
				? "Sua candidatura foi aprovada. O painel de moderação já está liberado."
				: "Sua candidatura foi recusada" + (cleanNote == null ? "." : ": " + cleanNote),
			"/moderador", "Abrir painel");
		return applicationView(saved);
	}

	@Transactional
	public ApplicationView revoke(String userId, String reviewerId, String note) {
		ModeratorApplication approved = applications.findApprovedByUser(userId)
			.orElseThrow(() -> new ApiException(404, "NOT_MODERATOR", "Este usuário não é moderador."));
		ModeratorApplication revoked = approved.revoke(reviewerId, trimOrNull(note), Instant.now(clock));
		applications.save(revoked);
		notifications.create(userId, "MODERACAO", reviewerId, "Acesso de moderador revogado",
			"Seu acesso ao painel de moderação foi revogado por um administrador.",
			"/moderador", "Ver");
		return applicationView(revoked);
	}

	/**
	 * Nomeação direta por administrador. Se houver candidatura pendente, ela é aprovada; senão
	 * criamos uma candidatura já aprovada — o papel de moderador continua vindo só daqui.
	 */
	@Transactional
	public ApplicationView appoint(String username, String adminId, String note) {
		UserProfile target = profiles.findByUsername(trim(username))
			.orElseThrow(() -> new ApiException(404, "USER_NOT_FOUND", "Usuário não encontrado."));
		if (applications.findApprovedByUser(target.id()).isPresent()) {
			throw new ApiException(409, "ALREADY_MODERATOR", "Este usuário já é moderador.");
		}
		Instant now = Instant.now(clock);
		String cleanNote = trimOrNull(note);
		ModeratorApplication pending = applications.findLatestByUser(target.id())
			.filter(ModeratorApplication::isPending)
			.orElse(null);
		ModeratorApplication approved = pending == null
			? ModeratorApplication.create("modapp_" + randomId(), target.id(),
				"Nomeação direta por administrador.", null, null, now)
				.approve(adminId, cleanNote, now)
			: pending.approve(adminId, cleanNote, now);
		ModeratorApplication saved = applications.save(approved);
		notifications.create(target.id(), "MODERACAO", adminId, "Agora você é moderador",
			"Você foi nomeado moderador por um administrador. O painel de moderação já está liberado.",
			"/moderador", "Abrir painel");
		return applicationView(saved);
	}

	/** Moderadores ativos, para a lista de gestão da aba de candidaturas. */
	@Transactional(readOnly = true)
	public List<ApplicationView> approvedModerators(int limit) {
		return applications.findByStatus(ModeratorApplicationStatus.APPROVED, limit).stream()
			.map(this::applicationView)
			.toList();
	}

	@Transactional(readOnly = true)
	public boolean isModerator(String userId) {
		return applications.findApprovedByUser(userId).isPresent();
	}

	/** Fila de denúncias para o painel: conteúdo, autor e a contagem de votos de cada uma. */
	@Transactional(readOnly = true)
	public List<PanelReportView> panelReports(String moderatorId, int limit) {
		return reports.findByStatus(ReportStatus.PENDING, limit).stream().map(report -> {
			ModerationService.ReportReview review = moderationService.review(report.id());
			// Autor já banido: a denúncia não precisa de votação (o saneamento periódico a
			// encerra). Some do painel para não poluir a fila.
			if (review.authorId() != null && sanctionGate.banned(review.authorId())) {
				return null;
			}
			List<ModerationVote> all = votes.findByReport(report.id());
			int punish = (int) all.stream().filter(v -> v.vote() == ModerationVoteType.PUNISH).count();
			int dismiss = (int) all.stream().filter(v -> v.vote() == ModerationVoteType.DISMISS).count();
			ModerationVote mine = all.stream()
				.filter(v -> v.moderatorId().equals(moderatorId))
				.findFirst().orElse(null);
			return new PanelReportView(report.id(), report.targetType().name(), report.targetId(),
				report.reason().name(), report.description(), report.createdAt().toString(),
				review.reporter(), review.authorId(), review.author(), review.content(), punish, dismiss,
				mine == null ? null : mine.vote().name(), voteQuorum);
		}).filter(java.util.Objects::nonNull).toList();
	}

	@Transactional
	public VoteResult vote(String reportId, String moderatorId, ModerationVoteType type, String note) {
		if (type == null) {
			throw new ApiException(400, "INVALID_VOTE", "Escolha punir ou descartar.");
		}
		Report report = reports.findById(reportId)
			.orElseThrow(() -> new ApiException(404, "REPORT_NOT_FOUND", "Denúncia não encontrada."));
		if (report.status() != ReportStatus.PENDING) {
			throw new ApiException(409, "ALREADY_REVIEWED", "Esta denúncia já foi revisada.");
		}
		if (report.reporterId().equals(moderatorId)) {
			throw new ApiException(403, "REPORT_IS_YOURS", "Você denunciou este conteúdo; outro moderador decide.");
		}
		String authorId = contentAuthors.authorOf(report).orElse(null);
		if (moderatorId.equals(authorId)) {
			throw new ApiException(403, "AUTHOR_CANNOT_VOTE", "Você é o autor do conteúdo denunciado.");
		}

		String cleanNote = trimOrNull(note);
		ModerationVote existing = votes.findByReportAndModerator(reportId, moderatorId).orElse(null);
		Instant now = Instant.now(clock);
		ModerationVote saved = existing == null
			? votes.save(ModerationVote.create("mvote_" + randomId(), reportId, moderatorId, type, cleanNote, now))
			: votes.save(existing.changeTo(type, cleanNote, now));

		List<ModerationVote> all = votes.findByReport(reportId);
		int punish = (int) all.stream().filter(v -> v.vote() == ModerationVoteType.PUNISH).count();
		int dismiss = (int) all.stream().filter(v -> v.vote() == ModerationVoteType.DISMISS).count();

		String decision = "PENDING";
		String message = "Voto registrado. A denúncia segue em análise.";
		if (punish >= voteQuorum && punish > dismiss) {
			try {
				moderationService.penalize(reportId, moderatorId);
				decision = "PENALIZED";
				message = "Quórum atingido: autor restrito e conteúdo removido.";
			}
			catch (ApiException failure) {
				if (!"CANNOT_PENALIZE".equals(failure.code())) {
					throw failure;
				}
				decision = "NEEDS_ADMIN";
				message = "Quórum de punição atingido, mas este alvo precisa de decisão de um administrador.";
			}
		}
		else if (dismiss >= voteQuorum && dismiss > punish) {
			moderationService.dismiss(reportId, moderatorId,
				"Denúncia descartada por votação (" + punish + " a " + dismiss + ")");
			decision = "DISMISSED";
			message = "Quórum atingido: denúncia descartada.";
		}

		String status = reports.findById(reportId).map(r -> r.status().name()).orElse("PENDING");
		return new VoteResult(punish, dismiss, saved.vote().name(), status, decision, message, voteQuorum);
	}

	/**
	 * Punição direta de qualquer usuário por um administrador do painel. RESTRICTION tira o direito
	 * de publicar/comentar; BAN derruba as sessões e impede novo login; PERMABAN é o banimento
	 * definitivo (sem caminho de recurso oferecido no painel). Não é denúncia: fica registrado
	 * com a referência `admin:{id do admin}` para o recurso do usuário funcionar.
	 */
	@Transactional
	public SanctionResult sanctionUser(String adminId, String username, SanctionType type, String reason) {
		if (type == null) {
			throw new ApiException(400, "INVALID_SANCTION_TYPE", "Escolha restrição, banimento ou banimento permanente.");
		}
		String cleanReason = trim(reason);
		if (cleanReason.length() < 10) {
			throw new ApiException(400, "REASON_REQUIRED", "Explique o motivo da punição (pelo menos 10 caracteres).");
		}
		UserProfile target = profiles.findByUsername(trim(username))
			.orElseThrow(() -> new ApiException(404, "USER_NOT_FOUND", "Usuário não encontrado."));
		if (target.id().equals(adminId)) {
			throw new ApiException(400, "CANNOT_SANCTION_SELF", "Você não pode punir a própria conta.");
		}
		String reference = "admin:" + adminId;
		Optional<UserSanction> active = sanctions.findActiveByUser(target.id());
		if (type != SanctionType.RESTRICTION) {
			if (active.isPresent() && active.get().isBlocked()) {
				return new SanctionResult(target.id(), target.username(), type.name(), "ALREADY_BANNED");
			}
			// Reaproveita a restrição ativa (mesmo id) em vez de criar uma segunda sanção ACTIVE.
			UserSanction sanction = active
				.map(existing -> existing.escalateTo(type, cleanReason))
				.orElseGet(() -> type == SanctionType.PERMABAN
					? UserSanction.permaBan("sanc_" + randomId(), target.id(), reference, cleanReason,
						Instant.now(clock))
					: UserSanction.suspended("sanc_" + randomId(), target.id(), reference, cleanReason,
						Instant.now(clock)));
			sanctionService.banAuthor(sanction, target.id(), adminId, sessions);
		}
		else {
			if (active.isPresent() && active.get().isBlocked()) {
				return new SanctionResult(target.id(), target.username(), type.name(), "ALREADY_BANNED");
			}
			sanctionService.restrictUser(target.id(), reference, cleanReason);
		}
		return new SanctionResult(target.id(), target.username(), type.name(), "ACTIVE");
	}

	/**
	 * Banimento com bloqueio de todos os IPs conhecidos do usuario. Aplica PERMABAN (sem recurso)
	 * e bloqueia cada IP de onde ele entrou, impedindo signup e login daquele endereco. Somente
	 * admin — o endpoint no controller exige {@code adminGuard}.
	 */
	@Transactional
	public BanWithIpsResult banWithIps(String adminId, String username, String reason) {
		SanctionResult sanction = sanctionUser(adminId, username, SanctionType.PERMABAN, reason);
		List<IpBlockService.UserIpView> ips = ipBlockService.ipsOfUser(username);
		List<String> blocked = new java.util.ArrayList<>();
		List<String> alreadyBlocked = new java.util.ArrayList<>();
		for (IpBlockService.UserIpView ip : ips) {
			try {
				ipBlockService.block(adminId, ip.ip(), "IP de @" + trim(username) + ": " + trim(reason));
				blocked.add(ip.ip());
			}
			catch (ApiException e) {
				if ("IP_ALREADY_BLOCKED".equals(e.code())) {
					alreadyBlocked.add(ip.ip());
				}
				// IP invalido ou outro erro: pula sem travar a operacao.
			}
		}
		return new BanWithIpsResult(sanction, blocked, alreadyBlocked);
	}

	public record BanWithIpsResult(SanctionResult sanction, List<String> blockedIps, List<String> alreadyBlockedIps) {
	}

	/**
	 * Sanitização do usuário (limpeza por spam): APAGA DO BANCO todos os posts que ele publicou
	 * e os comentários que ele escreveu, com reações, selos, salvamentos e mídias anexadas.
	 * Diferente da remoção por moderação (soft delete): aqui a linha sai do banco de verdade.
	 */
	@Transactional
	public SanitizeResult sanitizeUser(String adminId, String username, String reason) {
		String cleanReason = trim(reason);
		if (cleanReason.length() < 10) {
			throw new ApiException(400, "REASON_REQUIRED", "Explique o motivo da sanitização (pelo menos 10 caracteres).");
		}
		UserProfile target = profiles.findByUsername(trim(username))
			.orElseThrow(() -> new ApiException(404, "USER_NOT_FOUND", "Usuário não encontrado."));
		if (target.id().equals(adminId)) {
			throw new ApiException(400, "CANNOT_SANITIZE_SELF", "Você não pode sanitizar a própria conta.");
		}
		var result = contentPurge.purgeAllByAuthor(adminId, target.id(), cleanReason);
		return new SanitizeResult(target.id(), target.username(), result.posts(), result.comments(),
			result.otherRows());
	}

	public record SanitizeResult(String userId, String username, int posts, int comments, int otherRows) {
	}

	private static String randomId() {
		return UUID.randomUUID().toString().replace("-", "");
	}

	private static String trim(String value) {
		return value == null ? "" : value.trim();
	}

	private static String trimOrNull(String value) {
		String trimmed = trim(value);
		return StringUtils.hasText(trimmed) ? trimmed : null;
	}

	/** Visão da candidatura com quem é o candidato — o admin precisa saber a quem está aprovando. */
	private ApplicationView applicationView(ModeratorApplication application) {
		UserProfile profile = profiles.findById(application.userId()).orElse(null);
		return new ApplicationView(application.id(), application.userId(),
			profile == null ? null : profile.username(),
			profile == null ? null : profile.displayName(),
			application.motivation(), application.experience(), application.availability(),
			application.status().name(), application.createdAt().toString(), application.reviewedBy(),
			application.reviewedAt() == null ? null : application.reviewedAt().toString(),
			application.reviewNote());
	}

	public record MeView(int reputation, int minReputation, boolean eligible, boolean moderator,
			long moderatorCount, int minModeratorsForSanction, ApplicationView application) {
	}

	public record ApplicationView(String id, String userId, String username, String name,
			String motivation, String experience, String availability, String status, String createdAt,
			String reviewedBy, String reviewedAt, String reviewNote) {
	}

	public record PanelReportView(String reportId, String targetType, String targetId, String reason,
			String description, String createdAt, String reporter, String authorId, String author,
			String content, int punishVotes, int dismissVotes, String myVote, int quorum) {
	}

	public record VoteResult(int punishVotes, int dismissVotes, String myVote, String reportStatus,
			String decision, String message, int quorum) {
	}

	public record SanctionResult(String userId, String username, String type, String status) {
	}
}
