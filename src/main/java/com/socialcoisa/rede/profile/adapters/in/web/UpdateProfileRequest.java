package com.socialcoisa.rede.profile.adapters.in.web;

import java.util.List;

public record UpdateProfileRequest(String name, String headline, String company, String location, String avatar,
		String banner, String bio, List<String> skills, List<String> goals, String availability, String portfolio,
		List<String> externalLinks, Boolean openToWork, Boolean isMentor, String username) {
}
