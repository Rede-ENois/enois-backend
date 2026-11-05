package com.socialcoisa.rede.projects.domain;

import java.time.Instant;
import java.util.List;

/**
 * Um card de projeto do perfil: stack, link do repositório, status e o diário de
 * evolução. Quando {@code html} existe, o card INTEIRO vira o HTML do autor
 * (sanitizado na gravação) e a parte estruturada fica de reserva.
 */
public record UserProject(String id, String userId, int position, String title, String description,
		String banner, String icon, List<String> stack, String repoUrl, String status,
		List<ProjectDiaryEntry> diary, String html, Instant createdAt, Instant updatedAt) {

	}
