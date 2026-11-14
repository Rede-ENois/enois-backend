package com.socialcoisa.rede.communityprojects.application;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.communityprojects.application.port.out.CommunityProjectRepository;
import com.socialcoisa.rede.communityprojects.domain.CommunityProject;
import com.socialcoisa.rede.communityprojects.domain.CommunityProjectStatus;
import com.socialcoisa.rede.feed.application.port.out.OutboxEventRepository;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Projetos da comunidade: módulo próprio, sem tocar na tabela/feature de jogos.
 *
 * Ciclo igual ao dos jogos — envio, revisão, publicação —, mas a página é de projeto: link do
 * projeto e do repositório, categoria e descrição. Nada de player/iframe.
 */
@Service
public class CommunityProjectService {

	private final CommunityProjectRepository projects;
	private final UserProfileRepository profiles;
	private final Clock clock;
	private final OutboxEventRepository outbox;

	private final com.socialcoisa.rede.shared.sanctions.SanctionGateReader sanctionGate;

	public CommunityProjectService(CommunityProjectRepository projects, UserProfileRepository profiles,
			com.socialcoisa.rede.shared.sanctions.SanctionGateReader sanctionGate, Clock clock, OutboxEventRepository outbox) {
		this.sanctionGate = sanctionGate;
		this.projects = projects;
		this.profiles = profiles;
		this.clock = clock;
		this.outbox = outbox;
	}

	@Transactional
	public CommunityProject submit(String userId, String slugBase, String title, String description, String shortDescription,
			String cover, String url, String repoUrl, String tags, String category) {
		if (!StringUtils.hasText(title) || title.trim().length() < 3) {
			throw new ApiException(400, "INVALID_TITLE", "Título do projeto precisa de pelo menos 3 caracteres.");
		}
		String slug = uniqueSlug(userId, StringUtils.hasText(slugBase) ? slugFrom(slugBase) : slugFrom(title));
		CommunityProject project = CommunityProject.submit("proj_" + UUID.randomUUID().toString().replace("-", ""), userId, slug,
			title.trim(), trim(description), trim(shortDescription), trim(cover), safeUrl(url), safeUrl(repoUrl),
			trim(tags), trim(category), clock.instant());
		return projects.save(project);
	}

	@Transactional
	public CommunityProject edit(String projectId, String userId, String title, String description, String shortDescription,
			String cover, String url, String repoUrl, String tags, String category, String newSlug) {
		CommunityProject project = ownedForUpdate(projectId, userId);
		if (!StringUtils.hasText(title) || title.trim().length() < 3) {
			throw new ApiException(400, "INVALID_TITLE", "Título do projeto precisa de pelo menos 3 caracteres.");
		}
		String slug = null;
		if (StringUtils.hasText(newSlug)) {
			String candidate = slugFrom(newSlug);
			if (!candidate.equals(project.slug())) {
				if (candidate.length() < 3) {
					throw new ApiException(400, "INVALID_SLUG",
						"O endereço do projeto precisa de pelo menos 3 caracteres (letras, números ou hífen).");
				}
				if (projects.existsByUserAndSlug(userId, candidate)) {
					throw new ApiException(409, "SLUG_IN_USE", "Você já tem um projeto nesse endereço. Escolha outro.");
				}
				slug = candidate;
			}
		}
		return projects.save(project.edit(title.trim(), trim(description), trim(shortDescription), trim(cover),
			safeUrl(url), safeUrl(repoUrl), trim(tags), trim(category), slug, clock.instant()));
	}

	@Transactional
	public CommunityProject resubmit(String projectId, String userId) {
		CommunityProject project = ownedForUpdate(projectId, userId);
		if (project.status() != CommunityProjectStatus.REJECTED) {
			throw new ApiException(409, "NOT_REJECTED", "Só projeto recusado pode ser reenviado.");
		}
		return projects.save(project.resubmit(clock.instant()));
	}

	@Transactional
	public void remove(String projectId, String userId) {
		ownedForUpdate(projectId, userId);
		projects.delete(projectId);
	}

	@Transactional
	public CommunityProject approve(String projectId, String reviewerId, String note) {
		CommunityProject project = projectForUpdate(projectId);
		if (project.status() != CommunityProjectStatus.SUBMITTED) {
			throw new ApiException(409, "NOT_SUBMITTED", "Este projeto não está na fila de revisão.");
		}
		CommunityProject published = projects.save(project.approve(reviewerId, trim(note), clock.instant()));
		if (project.publishedAt() == null) {
			outbox.append("CommunityProjectPublished", project.id(), "{\"afterUserId\":\"\"}");
		}
		return published;
	}

	@Transactional
	public CommunityProject reject(String projectId, String reviewerId, String note) {
		CommunityProject project = projectForUpdate(projectId);
		if (project.status() != CommunityProjectStatus.SUBMITTED) {
			throw new ApiException(409, "NOT_SUBMITTED", "Este projeto não está na fila de revisão.");
		}
		if (!StringUtils.hasText(note)) {
			throw new ApiException(400, "REVIEW_NOTE_REQUIRED", "A recusa precisa de um motivo.");
		}
		return projects.save(project.reject(reviewerId, note.trim(), clock.instant()));
	}

	@Transactional
	public CommunityProject suspend(String projectId, String reviewerId, String note) {
		CommunityProject project = projectForUpdate(projectId);
		if (project.status() == CommunityProjectStatus.SUSPENDED) {
			throw new ApiException(409, "ALREADY_SUSPENDED", "Este projeto já está suspenso.");
		}
		return projects.save(project.suspend(reviewerId, trim(note), clock.instant()));
	}

	@Transactional
	public CommunityProject unsuspend(String projectId, String reviewerId) {
		CommunityProject project = projectForUpdate(projectId);
		if (project.status() != CommunityProjectStatus.SUSPENDED) {
			throw new ApiException(409, "NOT_SUSPENDED", "Este projeto não está suspenso.");
		}
		return projects.save(project.restoreFromSuspension(reviewerId, clock.instant()));
	}

	@Transactional(readOnly = true)
	public CommunityProject byUserAndSlug(String username, String slug, String viewerId) {
		CommunityProject project = projects.findByUserAndSlug(userIdFromUsername(username), slug)
			.orElseThrow(() -> new ApiException(404, "PROJECT_NOT_FOUND", "Projeto não encontrado."));
		boolean owner = viewerId != null && project.ownedBy(viewerId);
		if (!project.isPublished() && !owner) {
			throw new ApiException(404, "PROJECT_NOT_FOUND", "Projeto não encontrado.");
		}
		return project;
	}

	@Transactional(readOnly = true)
	public List<CommunityProject> publishedByUser(String username) {
		return projects.findByUserAndStatus(userIdFromUsername(username), CommunityProjectStatus.PUBLISHED, 100);
	}

	@Transactional(readOnly = true)
	public List<CommunityProject> ownedBy(String userId) {
		return projects.findByUser(userId);
	}

	@Transactional(readOnly = true)
	public List<CommunityProject> pending(int limit) {
		return projects.findByStatus(CommunityProjectStatus.SUBMITTED, Math.min(100, Math.max(1, limit)));
	}

	@Transactional(readOnly = true)
	public CommunityProjectRepository.SearchPage search(String query, String order, int limit, int offset) {
		return projects.search(query, order, Math.min(48, Math.max(1, limit)), Math.max(0, offset));
	}

	@Transactional(readOnly = true)
	public String usernameOf(String userId) {
		return profiles.findById(userId).map(p -> p.username()).orElse(null);
	}

	private String userIdFromUsername(String username) {
		return profiles.findByUsername(username).map(p -> p.id())
			.filter(userId -> !sanctionGate.banned(userId))
			.orElseThrow(() -> new ApiException(404, "PROJECT_NOT_FOUND", "Criador não encontrado."));
	}

	private CommunityProject projectForUpdate(String id) {
		return projects.findByIdForUpdate(id)
			.orElseThrow(() -> new ApiException(404, "PROJECT_NOT_FOUND", "Projeto não encontrado."));
	}

	private CommunityProject ownedForUpdate(String id, String userId) {
		CommunityProject project = projectForUpdate(id);
		if (!project.ownedBy(userId)) {
			throw new ApiException(403, "NOT_AUTHORIZED", "Este projeto não é seu.");
		}
		return project;
	}

	private String uniqueSlug(String userId, String base) {
		String slug = StringUtils.hasText(base) ? base : "projeto";
		String candidate = slug;
		int n = 2;
		while (projects.existsByUserAndSlug(userId, candidate)) {
			candidate = slug + "-" + n++;
		}
		return candidate;
	}

	private static String slugFrom(String value) {
		return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
			.replaceAll("\\p{M}+", "")
			.toLowerCase(Locale.ROOT)
			.replaceAll("[^a-z0-9]+", "-")
			.replaceAll("(^-|-$)", "");
	}

	/** Link de projeto/repositório: só http(s) público, nunca javascript/data/file. */
	private static String safeUrl(String value) {
		return StringUtils.hasText(value) ? com.socialcoisa.rede.shared.web.SafeUrl.requireHttpUrl(value, "link") : null;
	}

	private static String trim(String value) {
		return StringUtils.hasText(value) ? value.trim() : null;
	}
}
