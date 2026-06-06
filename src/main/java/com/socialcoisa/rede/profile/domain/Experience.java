package com.socialcoisa.rede.profile.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public class Experience {

	private final String id;
	private final String userId;
	private String title;
	private String company;
	private String location;
	private String description;
	private boolean currentlyHere;
	private LocalDate startDate;
	private LocalDate endDate;
	private int displayOrder;
	private final Instant createdAt;
	private Instant updatedAt;

	private Experience(String id, String userId, String title, String company, String location, String description,
			boolean currentlyHere, LocalDate startDate, LocalDate endDate, int displayOrder, Instant createdAt,
			Instant updatedAt) {
		this.id = id;
		this.userId = userId;
		this.title = title;
		this.company = company;
		this.location = location;
		this.description = description;
		this.currentlyHere = currentlyHere;
		this.startDate = startDate;
		this.endDate = endDate;
		this.displayOrder = displayOrder;
		this.createdAt = createdAt;
		this.updatedAt = updatedAt;
	}

	public static Experience create(String userId, String title, String company, String location, String description,
			LocalDate startDate, LocalDate endDate, int displayOrder, Instant now) {
		return new Experience(UUID.randomUUID().toString(), userId, title, company, location, description,
			endDate == null, startDate, endDate, displayOrder, now, now);
	}

	public static Experience restore(String id, String userId, String title, String company, String location,
			String description, boolean currentlyHere, LocalDate startDate, LocalDate endDate, int displayOrder,
			Instant createdAt, Instant updatedAt) {
		return new Experience(id, userId, title, company, location, description, currentlyHere, startDate, endDate,
			displayOrder, createdAt, updatedAt);
	}

	public void update(String title, String company, String location, String description, LocalDate startDate,
			LocalDate endDate, Instant now) {
		this.title = title;
		this.company = company;
		this.location = location;
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

	public String title() {
		return title;
	}

	public String company() {
		return company;
	}

	public String location() {
		return location;
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
