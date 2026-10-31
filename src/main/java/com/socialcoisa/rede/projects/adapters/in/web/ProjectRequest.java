package com.socialcoisa.rede.projects.adapters.in.web;

import java.util.List;

import com.socialcoisa.rede.projects.domain.ProjectDiaryEntry;
import com.socialcoisa.rede.projects.domain.UserProject;

/** Corpo de criação/edição de um card de projeto. */
public record ProjectRequest(String title, String description, String banner, String icon, List<String> stack,
		String repoUrl, String status, List<ProjectDiaryEntry> diary, String html) {

	public UserProject toDomain() {
		return new UserProject(null, null, 0, title(), description(), banner(), icon(), stack() == null ? List.of() : stack(), repoUrl(),
			status(), diary() == null ? List.of() : diary(), html(), null, null);
	}
}
