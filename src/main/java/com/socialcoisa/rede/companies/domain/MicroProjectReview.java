package com.socialcoisa.rede.companies.domain;

import java.time.Instant;

/** The company's review of the professional it selected, written when it completes the project. */
public record MicroProjectReview(String projectId, String companyId, String userId, int rating, String text,
		Instant createdAt) {
}
