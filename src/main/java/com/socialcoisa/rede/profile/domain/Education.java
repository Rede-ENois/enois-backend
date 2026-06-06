package com.socialcoisa.rede.profile.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public class Education {

	private final String id;
	private final String userId;
	private String school;
	private String degree;
	private String field;
	private String description;
	private boolean currentlyHere;
	private LocalDate startDate;
	private LocalDate endDate;
	private int displayOrder;
	private final Instant createdAt;
	private Instant updatedAt;

	private Education(String id, String userId, String school, String degree, String field, String description,
			boolean currentlyHere, LocalDate startDate, LocalDate endDate, int displayOrder, Instant createdAt,
			Instant updatedAt) {
		this.id = id;
		this.userId = userId;
		this.school = school;
		this.degree = degree;
		this.field = field;
		this.description = description;
		this.currentlyHere = currentlyHere;
		this.startDate = startDate;
		this.endDate = endDate;
		this.displayOrder = displayOrder;
		this.createdAt = createdAt;
		this.updatedAt = updatedAt;
	}

	public static Education create(String userId, String school, String degree, String field, String description,
			LocalDate startDate, LocalDate endDate, int displayOrder, Instant now) {
		return new Education(UUID.randomUUID().toString(), userId, school, degree, field, description, endDate == null,
			startDate, endDate, displayOrder, now, now);
	}

	public static Education restore(String id, String userId, String school, String degree, String field,
			String description, boolean currentlyHere, LocalDate startDate, LocalDate endDate, int displayOrder,
			Instant createdAt, Instant updatedAt) {
		return new Education(id, userId, school, degree, field, description, currentlyHere, startDate, endDate,
			displayOrder, createdAt, updatedAt);
	}

	public void update(String school, String degree, String field, String description, LocalDate startDate,
			LocalDate endDate, Instant now) {
		this.school = school;
		this.degree = degree;
		this.field = field;
		this.description = description;
		this.currentlyHere = endDate == null;
		this.startDate = startDate;
		this.endDate = endDate;
		this.updatedAt = now;
	}

	public String id() {
		return id;
	}

	public String userId() {
		return userId;
	}

	public String school() {
		return school;
	}

	public String degree() {
		return degree;
	}

	public String field() {
		return field;
	}

	public String description() {
		return description;
	}

	public boolean currentlyHere() {
		return currentlyHere;
	}

	public LocalDate startDate() {
		return startDate;
	}

	public LocalDate endDate() {
		return endDate;
	}

	public int displayOrder() {
		return displayOrder;
	}

	public Instant createdAt() {
		return createdAt;
	}

	public Instant updatedAt() {
		return updatedAt;
	}

}
