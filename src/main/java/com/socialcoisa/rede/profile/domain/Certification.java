package com.socialcoisa.rede.profile.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public class Certification {

	private final String id;
	private final String userId;
	private String name;
	private String issuer;
	private String description;
	private LocalDate issueDate;
	private LocalDate expirationDate;
	private String credentialUrl;
	private final Instant createdAt;
	private Instant updatedAt;

	private Certification(String id, String userId, String name, String issuer, String description, LocalDate issueDate,
			LocalDate expirationDate, String credentialUrl, Instant createdAt, Instant updatedAt) {
		this.id = id;
		this.userId = userId;
		this.name = name;
		this.issuer = issuer;
		this.description = description;
		this.issueDate = issueDate;
		this.expirationDate = expirationDate;
		this.credentialUrl = credentialUrl;
		this.createdAt = createdAt;
		this.updatedAt = updatedAt;
	}

	public static Certification create(String userId, String name, String issuer, String description, LocalDate issueDate,
			LocalDate expirationDate, String credentialUrl, Instant now) {
		return new Certification(UUID.randomUUID().toString(), userId, name, issuer, description, issueDate,
			expirationDate, credentialUrl, now, now);
	}

	public static Certification restore(String id, String userId, String name, String issuer, String description,
			LocalDate issueDate, LocalDate expirationDate, String credentialUrl, Instant createdAt, Instant updatedAt) {
		return new Certification(id, userId, name, issuer, description, issueDate, expirationDate, credentialUrl,
			createdAt, updatedAt);
	}

	public void update(String name, String issuer, String description, LocalDate issueDate, LocalDate expirationDate,
			String credentialUrl, Instant now) {
		this.name = name;
		this.issuer = issuer;
		this.description = description;
		this.issueDate = issueDate;
		this.expirationDate = expirationDate;
		this.credentialUrl = credentialUrl;
		this.updatedAt = now;
	}

	public String id() {
		return id;
	}

	public String userId() {
		return userId;
	}

	public String name() {
		return name;
	}

	public String issuer() {
		return issuer;
	}

	public String description() {
		return description;
	}

	public LocalDate issueDate() {
		return issueDate;
	}

	public LocalDate expirationDate() {
		return expirationDate;
	}

	public String credentialUrl() {
		return credentialUrl;
	}

	public Instant createdAt() {
		return createdAt;
	}

	public Instant updatedAt() {
		return updatedAt;
	}

}
