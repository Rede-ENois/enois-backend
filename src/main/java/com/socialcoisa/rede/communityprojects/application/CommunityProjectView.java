package com.socialcoisa.rede.communityprojects.application;

import java.util.List;

import com.socialcoisa.rede.communityprojects.domain.CommunityProject;

/** Projeto como o front consome: ciclo de vida + campos da vitrine. */
public record CommunityProjectView(String id, String username, String slug, String title, String description,
		String shortDescription, String cover, String url, String repoUrl, String tags, String category,
		String status, String reviewNote, String submittedAt, String publishedAt, boolean owned) {

	public static CommunityProjectView of(CommunityProject project, String username, String viewerId) {
		return new CommunityProjectView(project.id(), username, project.slug(), project.title(),
			project.description(), project.shortDescription(), project.cover(), project.url(), project.repoUrl(),
			project.tags(), project.category(), project.status().name(), project.reviewNote(),
			project.submittedAt().toString(), project.publishedAt() == null ? null : project.publishedAt().toString(),
			viewerId != null && project.ownedBy(viewerId));
	}

	public static List<String> split(String csv) {
		if (csv == null || csv.isBlank()) {
			return List.of();
		}
		return java.util.Arrays.stream(csv.split(",")).map(String::trim).filter(v -> !v.isEmpty()).toList();
	}
}
