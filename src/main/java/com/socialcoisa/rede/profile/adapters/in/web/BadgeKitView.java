package com.socialcoisa.rede.profile.adapters.in.web;

import java.util.List;

import com.socialcoisa.rede.profile.domain.BadgeKit;

/** Um kit do histórico, como a tela de seleção de emblemas o exibe. */
record BadgeKitView(String id, List<String> badges, String createdAt) {

	static BadgeKitView from(BadgeKit kit) {
		return new BadgeKitView(kit.id(), kit.badges(), kit.createdAt() == null ? null : kit.createdAt().toString());
	}
}