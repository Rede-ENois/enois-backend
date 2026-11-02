package com.socialcoisa.rede.projects.adapters.in.web;

import java.util.List;

import com.socialcoisa.rede.projects.domain.ProjectDiaryEntry;
import com.socialcoisa.rede.projects.domain.UserProject;

/** Card de projeto exposto no perfil. O HTML (quando existe) já vai sanitizado. */
public record ProjectView(String id, String title, String description, String banner, String icon, List<String> stack,
		String repoUrl, String status, List<ProjectDiaryEntry> diary, String html) {

	public static ProjectView from(UserProject project) {
		return new ProjectView(project.id(), project.title(), project.description(), project.banner(), project.icon(), project.stack(),
			project.repoUrl(), project.status(), project.diary(), project.html());
	}
}
