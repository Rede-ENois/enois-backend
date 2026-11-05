package com.socialcoisa.rede.projects.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.projects.application.port.out.UserProjectRepository;
import com.socialcoisa.rede.projects.domain.ProjectDiaryEntry;
import com.socialcoisa.rede.projects.domain.UserProject;
import com.socialcoisa.rede.shared.html.SafeHtml;
import com.socialcoisa.rede.shared.text.PlainText;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Cards de projeto do perfil: stack, repositório, status e diário de evolução — ou o
 * card inteiro como HTML próprio (sanitizado na gravação, igual à bio e à página).
 *
 * Limites iguais para todo mundo, aplicados na criação e na edição. O HTML quando
 * existe SUBSTITUI a parte estruturada na exibição (os dados continuam guardados).
 */
@Service
public class ProjectsService {

	private static final int MAX_STACK_ITEMS = 10;
	private static final int MAX_DIARY_ENTRIES = 50;
	/** Entrada do diário: cabe a descrição de um projeto do LinkedIn (até 2.000 caracteres). */
	private static final int MAX_DIARY_TEXT = 2000;
	private static final int MAX_PROJECTS = 20;

	private final UserProjectRepository repository;
	private final com.socialcoisa.rede.profile.application.port.out.UserProfileRepository profiles;
	private final Clock clock;

	public ProjectsService(UserProjectRepository repository,
			com.socialcoisa.rede.profile.application.port.out.UserProfileRepository profiles, Clock clock) {
		this.repository = repository;
		this.profiles = profiles;
		this.clock = clock;
	}

	/** Projetos públicos de alguém (a tela de perfil busca por username). */
	@Transactional(readOnly = true)
	public List<UserProject> byUsername(String username) {
		return profiles.findByUsername(username)
			.map(profile -> repository.findByUser(profile.id()))
			.orElseThrow(() -> new ApiException(404, "PROFILE_NOT_FOUND", "Perfil nao encontrado."));
	}

	@Transactional(readOnly = true)
	public List<UserProject> of(String userId) {
		return repository.findByUser(userId);
	}

	@Transactional
	public UserProject create(String userId, UserProject input) {
		if (repository.findByUser(userId).size() >= MAX_PROJECTS) {
			throw new ApiException(400, "MAX_PROJECTS", "Sao no maximo " + MAX_PROJECTS + " projetos por perfil.");
		}
		String id = "proj_" + UUID.randomUUID().toString().replace("-", "");
		return repository.save(build(id, userId, input, repository.findByUser(userId).size()));
	}

	@Transactional
	public UserProject update(String userId, String projectId, UserProject input) {
		UserProject existing = owned(userId, projectId);
		return repository.save(build(existing.id(), userId, input, existing.position()));
	}

	@Transactional
	public void delete(String userId, String projectId) {
		UserProject existing = owned(userId, projectId);
		repository.delete(existing.id());
	}

	private UserProject owned(String userId, String projectId) {
		UserProject project = repository.findById(projectId)
			.orElseThrow(() -> new ApiException(404, "PROJECT_NOT_FOUND", "Projeto nao encontrado."));
		if (!project.userId().equals(userId)) {
			throw new ApiException(404, "PROJECT_NOT_FOUND", "Projeto nao encontrado.");
		}
		return project;
	}

	private UserProject build(String id, String userId, UserProject input, int position) {
		Instant now = Instant.now(clock);
		String title = PlainText.defuse(input.title());
		if (title == null || title.isBlank()) {
			throw new ApiException(400, "INVALID_TITLE", "O projeto precisa de um titulo.");
		}
		if (title.length() > 120) {
			throw new ApiException(400, "FIELD_TOO_LONG", "Titulo deve ter no maximo 120 caracteres.");
		}
		String description = PlainText.defuse(input.description());
		check(description, 500, "a descricao do projeto");
		if (input.stack().size() > MAX_STACK_ITEMS) {
			throw new ApiException(400, "INVALID_STACK", "Sao no maximo " + MAX_STACK_ITEMS + " tecnologias.");
		}
		List<String> stack = input.stack().stream()
			.map(PlainText::defuse)
			.map(item -> item == null ? "" : item.trim())
			.filter(item -> !item.isEmpty())
			.toList();
		for (String item : stack) {
			check(item, 40, "cada tecnologia");
		}
		String repoUrl = httpsUrl(input.repoUrl(), "INVALID_REPO_URL", "O link do repositorio deve ser https.");
		String banner = httpsUrl(input.banner(), "INVALID_BANNER", "O banner do projeto deve ser uma URL https.");
		String icon = httpsUrl(input.icon(), "INVALID_ICON", "O icone do projeto deve ser uma URL https.");
		String status = PlainText.defuse(input.status());
		check(status, 40, "o status");
		if (input.diary().size() > MAX_DIARY_ENTRIES) {
			throw new ApiException(400, "INVALID_DIARY", "Sao no maximo " + MAX_DIARY_ENTRIES + " entradas no diario.");
		}
		List<ProjectDiaryEntry> diary = new ArrayList<>();
		for (var entry : input.diary()) {
			String text = PlainText.defuse(entry.text());
			check(text, MAX_DIARY_TEXT, "cada entrada do diario");
			String date = entry.date() == null ? "" : entry.date().trim();
			check(date, 10, "a data do diario");
			diary.add(new ProjectDiaryEntry(date, text == null ? "" : text));
		}
		String html = SafeHtml.sanitizeProjectHtml(input.html());
		if (html == null && input.html() != null && !input.html().isBlank()) {
			throw new ApiException(400, "INVALID_PROJECT_HTML", "HTML do projeto precisa de conteudo valido.");
		}
		return new UserProject(id, userId, position, title.trim(), description, banner, icon, stack, repoUrl,
			status == null || status.isBlank() ? null : status.trim(), diary, html, now, now);
	}

	/** Link de imagem/repositorio: so https (o mesmo guarda do repositorio, com erro proprio). */
	private String httpsUrl(String raw, String code, String message) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String url = raw.trim();
		if (!url.startsWith("https://")) {
			throw new ApiException(400, code, message);
		}
		check(url, 500, "o link");
		return url;
	}

	private void check(String value, int max, String label) {
		if (value != null && value.length() > max) {
			throw new ApiException(400, "FIELD_TOO_LONG", label + " deve ter no maximo " + max + " caracteres.");
		}
	}
}
