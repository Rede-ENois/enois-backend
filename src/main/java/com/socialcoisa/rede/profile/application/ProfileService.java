package com.socialcoisa.rede.profile.application;

import java.time.Clock;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.profile.application.port.in.GetMeUseCase;
import com.socialcoisa.rede.profile.application.port.in.GetUserProfileUseCase;
import com.socialcoisa.rede.profile.application.port.in.UpdateProfileUseCase;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;
import com.socialcoisa.rede.shared.web.ApiException;

@Service
public class ProfileService implements GetMeUseCase, GetUserProfileUseCase, UpdateProfileUseCase {

	private final UserProfileRepository repository;
	private final Clock clock;

	public ProfileService(UserProfileRepository repository, Clock clock) {
		this.repository = repository;
		this.clock = clock;
	}

	@Override
	@Transactional(readOnly = true)
	public UserProfileView getMe(String userId) {
		return repository.findById(userId)
			.map(UserProfileView::from)
			.orElseThrow(() -> new ApiException(404, "USER_NOT_FOUND", "Usuario nao encontrado."));
	}

	@Override
	@Transactional(readOnly = true)
	public UserProfileView getByUsername(String username) {
		return repository.findByUsername(username)
			.map(UserProfileView::from)
			.orElseThrow(() -> new ApiException(404, "USER_NOT_FOUND", "Usuario nao encontrado."));
	}

	@Override
	@Transactional
	public UserProfileView update(UpdateProfileCommand command) {
		UserProfile profile = repository.findById(command.userId())
			.orElseThrow(() -> new ApiException(404, "USER_NOT_FOUND", "Usuario nao encontrado."));
		profile.update(trim(command.name()), trim(command.headline()), trim(command.company()), trim(command.location()),
			trim(command.avatar()), trim(command.banner()), trim(command.bio()), clean(command.skills()),
			clean(command.goals()), trim(command.availability()), trim(command.portfolio()), clean(command.externalLinks()),
			command.openToWork(), command.isMentor(), clock.instant());
		return UserProfileView.from(repository.save(profile));
	}

	private String trim(String value) {
		return StringUtils.hasText(value) ? value.trim() : value;
	}

	private List<String> clean(List<String> values) {
		return values == null ? null : values.stream().filter(StringUtils::hasText).map(String::trim).distinct().toList();
	}
}
