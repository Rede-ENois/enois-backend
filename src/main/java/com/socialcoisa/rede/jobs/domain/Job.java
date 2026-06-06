package com.socialcoisa.rede.jobs.domain;

import java.time.Instant;
import java.util.List;

public class Job {

	private final String id;
	private final String title;
	private final String company;
	private final String logo;
	private final String location;
	private final String type;
	private final String level;
	private final String salary;
	private final List<String> skills;
	private final String postedBy;
	private final Instant postedAt;
	private final Instant updatedAt;

	private Job(String id, String title, String company, String logo, String location, String type, String level,
			String salary, List<String> skills, String postedBy, Instant postedAt, Instant updatedAt) {
		this.id = id;
		this.title = title;
		this.company = company;
		this.logo = logo;
		this.location = location;
		this.type = type;
		this.level = level;
		this.salary = salary;
		this.skills = skills;
		this.postedBy = postedBy;
		this.postedAt = postedAt;
		this.updatedAt = updatedAt;
	}

	public static Job create(String id, String title, String company, String logo, String location, String type,
			String level, String salary, List<String> skills, String postedBy, Instant now) {
		return new Job(id, title, company, logo, location, type, level, salary, List.copyOf(skills), postedBy, now, now);
	}

	public static Job restore(String id, String title, String company, String logo, String location, String type,
			String level, String salary, List<String> skills, String postedBy, Instant postedAt, Instant updatedAt) {
		return new Job(id, title, company, logo, location, type, level, salary, skills, postedBy, postedAt, updatedAt);
	}

	public String id() { return id; }

	public String title() { return title; }

	public String company() { return company; }

	public String logo() { return logo; }

	public String location() { return location; }

	public String type() { return type; }

	public String level() { return level; }

	public String salary() { return salary; }

	public List<String> skills() { return skills; }

	public String postedBy() { return postedBy; }

	public Instant postedAt() { return postedAt; }

	public Instant updatedAt() { return updatedAt; }

}
