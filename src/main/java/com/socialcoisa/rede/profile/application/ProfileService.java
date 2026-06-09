package com.socialcoisa.rede.profile.application;

import java.time.Clock;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.profile.application.port.in.GetMeUseCase;
import com.socialcoisa.rede.profile.application.port.in.GetUserProfileUseCase;
import com.socialcoisa.rede.profile.application.port.in.UpdateProfileUseCase;
import com.socialcoisa.rede.profile.application.port.out.ProfileExtensionRepository;
import com.socialcoisa.rede.profile.application.port.out.RecommendationRepository;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;
import com.socialcoisa.rede.shared.web.ApiException;

@Service
public class ProfileService implements GetMeUseCase, GetUserProfileUseCase, UpdateProfileUseCase {

	private final UserProfileRepository repository;
	private final ProfileExtensionRepository extensionRepository;
	private final RecommendationRepository recommendationRepository;
	private final Clock clock;

	public ProfileService(UserProfileRepository repository, ProfileExtensionRepository extensionRepository,
			RecommendationRepository recommendationRepository, Clock clock) {
		this.repository = repository;
		this.extensionRepository = extensionRepository;
		this.recommendationRepository = recommendationRepository;
		this.clock = clock;
	}

	@Override
	@Transactional(readOnly = true)
	public UserProfileView getMe(String userId) {
		return repository.findById(userId)
			.map(this::populateCounts)
			.map(UserProfileView::from)
			.orElseThrow(() -> new ApiException(404, "USER_NOT_FOUND", "Usuario nao encontrado."));
	}

	@Override
	@Transactional(readOnly = true)
	public UserProfileView getByUsername(String username) {
		return repository.findByUsername(username)
			.map(this::populateCounts)
			.map(UserProfileView::from)
			.orElseThrow(() -> new ApiException(404, "USER_NOT_FOUND", "Usuario nao encontrado."));
	}

	@Override
	@Transactional(readOnly = true)
	public UserProfileView getById(String userId) {
		return repository.findById(userId)
			.map(UserProfileView::from)
			.orElseThrow(() -> new ApiException(404, "USER_NOT_FOUND", "Usuario nao encontrado."));
	}

	private UserProfile populateCounts(UserProfile profile) {
		String userId = profile.id();
		int connections = repository.countConnections(userId);
		int followers = repository.countFollowers(userId);
		int experiences = extensionRepository.countExperiencesByUserId(userId);
		int educations = extensionRepository.countEducationsByUserId(userId);
		int certifications = extensionRepository.countCertificationsByUserId(userId);
		int postInteractions = repository.countPostInteractionsReceived(userId);
		int communities = repository.countCommunitiesJoined(userId);
		int events = repository.countEventsAttended(userId);
		int mentoring = repository.countMentoringSessionsAsMentee(userId);
		int reports = repository.countApprovedReports(userId);
		int recommendations = recommendationRepository.findByRecommendedId(userId).size();
		int reputation = Math.max(0, Math.min(
			experiences * 10 + educations * 10 + certifications * 5 + connections + followers / 2
				+ postInteractions + communities * 5 + events * 3 + mentoring * 10
				- reports * 50 + recommendations * 20,
			1000));
		profile.setCounts(connections, followers, reputation);
		repository.save(profile);
		return profile;
	}

	public List<UserProfileView> search(String query) {
		return repository.searchByQuery(query).stream()
			.map(UserProfileView::from)
			.toList();
	}

	public List<UserProfileView> getRanking(int limit) {
		return repository.findTopByReputation(limit).stream()
			.map(UserProfileView::from)
			.toList();
	}

	@Override
	@Transactional
	public UserProfileView update(UpdateProfileCommand command) {
		UserProfile profile = repository.findById(command.userId())
			.orElseThrow(() -> new ApiException(404, "USER_NOT_FOUND", "Usuario nao encontrado."));
		String username = trim(command.username());
		if (username != null && !username.equals(profile.username())) {
			String slug = slug(username);
			if (repository.existsByUsername(slug)) {
				throw new ApiException(409, "USERNAME_TAKEN", "Nome de usuario ja esta em uso.");
			}
			username = slug;
		}
		profile.update(trim(command.name()), trim(command.headline()), trim(command.company()), trim(command.location()),
			trim(command.avatar()), trim(command.banner()), trim(command.bio()), clean(command.skills()),
			clean(command.goals()), trim(command.availability()), trim(command.portfolio()), clean(command.externalLinks()),
			command.openToWork(), command.isMentor(), username, clock.instant());
		return UserProfileView.from(repository.save(profile));
	}

	private String slug(String value) {
		if (!StringUtils.hasText(value)) return null;
		String slug = value.toLowerCase(java.util.Locale.ROOT)
			.replaceAll("[^a-z0-9]+", "-")
			.replaceAll("(^-|-$)", "");
		return StringUtils.hasText(slug) ? slug : null;
	}

	private String trim(String value) {
		return StringUtils.hasText(value) ? value.trim() : value;
	}

	private List<String> clean(List<String> values) {
		return values == null ? null : values.stream().filter(StringUtils::hasText).map(String::trim).distinct().toList();
	}
}
