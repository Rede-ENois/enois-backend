package com.socialcoisa.rede.profile.domain;

import java.time.Instant;
import java.util.List;

public class UserProfile {

	private final String id;
	private final String username;
	private final String email;
	private String name;
	private String headline;
	private String company;
	private String location;
	private String avatar;
	private String banner;
	private String bio;
	private List<String> skills;
	private List<String> goals;
	private String availability;
	private String portfolio;
	private List<String> externalLinks;
	private boolean openToWork;
	private boolean mentor;
	private final Instant createdAt;
	private Instant updatedAt;

	private UserProfile(String id, String username, String email, String name, String headline, String company,
			String location, String avatar, String banner, String bio, List<String> skills, List<String> goals,
			String availability, String portfolio, List<String> externalLinks, boolean openToWork, boolean mentor,
			Instant createdAt, Instant updatedAt) {
		this.id = id;
		this.username = username;
		this.email = email;
		this.name = name;
		this.headline = headline;
		this.company = company;
		this.location = location;
		this.avatar = avatar;
		this.banner = banner;
		this.bio = bio;
		this.skills = List.copyOf(skills);
		this.goals = List.copyOf(goals);
		this.availability = availability;
		this.portfolio = portfolio;
		this.externalLinks = List.copyOf(externalLinks);
		this.openToWork = openToWork;
		this.mentor = mentor;
		this.createdAt = createdAt;
		this.updatedAt = updatedAt;
	}

	public static UserProfile create(String id, String username, String email, String name, String headline,
			Instant now) {
		return new UserProfile(id, username, email, name, headline, null, null, null, null, null, List.of(), List.of(),
			null, null, List.of(), false, false, now, now);
	}

	public static UserProfile restore(String id, String username, String email, String name, String headline,
			String company, String location, String avatar, String banner, String bio, List<String> skills,
			List<String> goals, String availability, String portfolio, List<String> externalLinks, boolean openToWork,
			boolean mentor, Instant createdAt, Instant updatedAt) {
		return new UserProfile(id, username, email, name, headline, company, location, avatar, banner, bio, skills,
			goals, availability, portfolio, externalLinks, openToWork, mentor, createdAt, updatedAt);
	}

	public void update(String name, String headline, String company, String location, String avatar, String banner,
			String bio, List<String> skills, List<String> goals, String availability, String portfolio,
			List<String> externalLinks, Boolean openToWork, Boolean mentor, Instant now) {
		this.name = keep(name, this.name);
		this.headline = keep(headline, this.headline);
		this.company = keep(company, this.company);
		this.location = keep(location, this.location);
		this.avatar = keep(avatar, this.avatar);
		this.banner = keep(banner, this.banner);
		this.bio = keep(bio, this.bio);
		this.skills = skills == null ? this.skills : List.copyOf(skills);
		this.goals = goals == null ? this.goals : List.copyOf(goals);
		this.availability = keep(availability, this.availability);
		this.portfolio = keep(portfolio, this.portfolio);
		this.externalLinks = externalLinks == null ? this.externalLinks : List.copyOf(externalLinks);
		this.openToWork = openToWork == null ? this.openToWork : openToWork;
		this.mentor = mentor == null ? this.mentor : mentor;
		this.updatedAt = now;
	}

	private String keep(String value, String current) {
		return value == null ? current : value.trim();
	}

	public String id() {
		return id;
	}

	public String username() {
		return username;
	}

	public String email() {
		return email;
	}

	public String name() {
		return name;
	}

	public String headline() {
		return headline;
	}

	public String company() {
		return company;
	}

	public String location() {
		return location;
	}

	public String avatar() {
		return avatar;
	}

	public String banner() {
		return banner;
	}

	public String bio() {
		return bio;
	}

	public List<String> skills() {
		return skills;
	}

	public List<String> goals() {
		return goals;
	}

	public String availability() {
		return availability;
	}

	public String portfolio() {
		return portfolio;
	}

	public List<String> externalLinks() {
		return externalLinks;
	}

	public boolean openToWork() {
		return openToWork;
	}

	public boolean mentor() {
		return mentor;
	}

	public Instant createdAt() {
		return createdAt;
	}

	public Instant updatedAt() {
		return updatedAt;
	}
}
