package com.socialcoisa.rede.projects.adapters.out.persistence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.socialcoisa.rede.projects.domain.ProjectDiaryEntry;
import com.socialcoisa.rede.projects.domain.UserProject;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

@Entity
@Table(name = "user_projects")
class UserProjectJpaEntity {

	/** O diário é JSON; o adapter injeta o mapper (a entidade não parsia nada à mão). */
	static ObjectMapper diaryMapper = new ObjectMapper();

	@Id
	@Column(name = "id", nullable = false, length = 40)
	private String id;

	@Column(name = "user_id", nullable = false)
	private String userId;

	@Column(name = "position", nullable = false)
	private int position;

	@Column(name = "title", nullable = false, length = 120)
	private String title;

	@Column(length = 500)
	private String description;

	/** Tecnologias separadas por linha (URL não tem quebra de linha; tecnologia também não). */
	@Column(name = "stack", nullable = false, columnDefinition = "text")
	private String stack;

	@Column(name = "repo_url", length = 500)
	private String repoUrl;

	@Column(length = 500)
	private String banner;

	@Column(length = 500)
	private String icon;

	@Column(name = "status", length = 40)
	private String status;

	/** Diário de evolução: JSON [{date, text}]. */
	@Column(name = "diary", columnDefinition = "longtext")
	private String diary;

	@Column(name = "html", columnDefinition = "longtext")
	private String html;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected UserProjectJpaEntity() {
	}

	static UserProjectJpaEntity fromDomain(UserProject project) {
		UserProjectJpaEntity entity = new UserProjectJpaEntity();
		entity.id = project.id();
		entity.userId = project.userId();
		entity.position = project.position();
		entity.title = project.title();
		entity.description = project.description();
		entity.banner = project.banner();
		entity.icon = project.icon();
		entity.stack = String.join("\n", project.stack());
		entity.repoUrl = project.repoUrl();
		entity.status = project.status();
		entity.diary = serializeDiary(project.diary());
		entity.html = project.html();
		entity.createdAt = project.createdAt();
		entity.updatedAt = project.updatedAt();
		return entity;
	}

	UserProject toDomain() {
		return new UserProject(id, userId, position, title, description, banner, icon,
			stack.isBlank() ? List.of() : List.of(stack.split("\n")), repoUrl, status, parseDiary(diary), html,
			createdAt, updatedAt);
	}

	private static String serializeDiary(List<ProjectDiaryEntry> diary) {
		if (diary == null || diary.isEmpty()) {
			return null;
		}
		ArrayNode array = diaryMapper.createArrayNode();
		diary.forEach(entry -> array.add(diaryMapper.createObjectNode().put("date", entry.date()).put("text", entry.text())));
		return diaryMapper.writeValueAsString(array);
	}

	private static List<ProjectDiaryEntry> parseDiary(String json) {
		if (json == null || json.isBlank()) {
			return List.of();
		}
		try {
			List<ProjectDiaryEntry> entries = new ArrayList<>();
			var nodes = diaryMapper.readTree(json);
			for (var node : nodes) {
				entries.add(new ProjectDiaryEntry(node.path("date").asText(""), node.path("text").asText("")));
			}
			return entries;
		} catch (Exception exception) {
			return List.of();
		}
	}
}
