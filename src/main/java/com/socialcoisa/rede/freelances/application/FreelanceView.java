package com.socialcoisa.rede.freelances.application;

import com.socialcoisa.rede.freelances.domain.Freelance;

public record FreelanceView(String id, String username, String title, String scope,
		int budgetMin, int budgetMax, String deadline, String paymentMethod, String status, String createdAt,
		boolean owned) {
	public static FreelanceView of(Freelance f, String username, String viewerId) {
		return new FreelanceView(f.id(), username, f.title(), f.scope(), f.budgetMin(),
			f.budgetMax(), f.deadline().toString(), f.paymentMethod().name(), f.status().name(),
			f.createdAt().toString(),
			viewerId != null && f.ownedBy(viewerId));
	}
}
