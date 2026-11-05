package com.socialcoisa.rede.onboarding.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.communities.application.CommunityService;
import com.socialcoisa.rede.groups.application.GroupService;
import com.socialcoisa.rede.groups.domain.Group;
import com.socialcoisa.rede.groups.domain.GroupJoinMode;
import com.socialcoisa.rede.network.application.NetworkService;
import com.socialcoisa.rede.onboarding.application.port.out.StarterPackReader;
import com.socialcoisa.rede.onboarding.application.port.out.StarterPackReader.CommunityCandidate;
import com.socialcoisa.rede.onboarding.application.port.out.StarterPackReader.GroupCandidate;
import com.socialcoisa.rede.preferences.application.PersonalizationService;
import com.socialcoisa.rede.preferences.domain.ContentTaxonomy;
import com.socialcoisa.rede.profile.application.ProfileService;
import com.socialcoisa.rede.profile.application.UpdateProfileCommand;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * First-run onboarding: suggests where to start (starter pack) and applies what the person picked.
 *
 * Every choice goes through the same service as the manual action (join a community, join a group,
 * ask to connect), so the same rules apply: a group that needs approval is never joined from here.
 * The caps keep it a human choice in a short flow, not a bulk tool.
 *
 * {@link #complete} is deliberately not one transaction: each join/request commits on its own, and
 * a retry skips what is already in place, so the call is idempotent and a single invalid id never
 * rolls back the rest.
 */
@Service
public class OnboardingService {

	static final int MAX_COMMUNITIES = 6;
	static final int MAX_GROUPS = 6;
	static final int MAX_PEOPLE = 8;
	static final int MAX_SKILLS = 20;
	/** Skills are short tags in this flow (the profile editor still accepts long free text). */
	static final int MAX_SKILL_LENGTH = 60;
	private static final int MAX_DESCRIPTION = 160;
	private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

	private final StarterPackReader reader;
	private final UserProfileRepository profiles;
	private final PersonalizationService personalization;
	private final ProfileService profileService;
	private final CommunityService communities;
	private final GroupService groups;
	private final NetworkService network;
	private final Clock clock;

	public OnboardingService(StarterPackReader reader, UserProfileRepository profiles,
			PersonalizationService personalization, ProfileService profileService, CommunityService communities,
			GroupService groups, NetworkService network, Clock clock) {
		this.reader = reader;
		this.profiles = profiles;
		this.personalization = personalization;
		this.profileService = profileService;
		this.communities = communities;
		this.groups = groups;
		this.network = network;
		this.clock = clock;
	}

	public record StarterPack(List<CommunityCard> communities, List<GroupCard> groups, List<PersonCard> people) {
	}

	public record CommunityCard(String id, String name, String description, String topic, String avatar,
			int memberCount) {
	}

	public record GroupCard(String id, String slug, String name, String description, String topic, String avatar,
			int memberCount) {
	}

	public record PersonCard(String id, String username, String name, String headline, String avatar) {
	}

	public record CompleteCommand(List<String> topics, List<String> skills, List<String> communityIds,
			List<String> groupIds, List<String> connectUserIds) {
	}

	/** What is in place after the call (already there or done now); skipped ids are left out. */
	public record CompleteResult(boolean onboardingCompleted, List<String> joinedCommunityIds,
			List<String> joinedGroupIds, List<String> requestedUserIds) {
	}

	public StarterPack starterPack(String userId, List<String> rawTopics, List<String> rawSkills) {
		List<String> topics = topics(rawTopics);
		List<String> skills = skills(rawSkills).stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
		List<CommunityCard> communityCards = reader.communities(userId, topics, MAX_COMMUNITIES).stream()
			.map(OnboardingService::card).toList();
		List<GroupCard> groupCards = reader.openGroups(userId, topics, MAX_GROUPS).stream()
			.map(OnboardingService::card).toList();
		return new StarterPack(communityCards, groupCards, people(userId, topics, skills));
	}

	/**
	 * People are suggested only between adults (ECA Digital): a minor gets no strangers suggested and
	 * is never suggested to anyone. The manual connection flow is unchanged.
	 */
	private List<PersonCard> people(String userId, List<String> topics, List<String> skills) {
		LocalDate today = LocalDate.now(clock.withZone(ZONE));
		UserProfile me = profiles.findById(userId)
			.orElseThrow(() -> new ApiException(404, "USER_NOT_FOUND", "Usuario nao encontrado."));
		if (me.birthDateDeclared() && me.isMinorOn(today)) {
			return List.of();
		}
		List<String> ids = reader.people(userId, topics, skills, today.minusYears(18), MAX_PEOPLE);
		Map<String, UserProfile> byId = profiles.findAllByIds(ids).stream()
			.collect(Collectors.toMap(UserProfile::id, Function.identity()));
		return ids.stream().map(byId::get).filter(p -> p != null)
			.map(p -> new PersonCard(p.id(), p.username(), p.displayName(), p.headline(), p.avatar()))
			.toList();
	}

	public CompleteResult complete(String userId, CompleteCommand command) {
		List<String> topics = topics(command.topics());
		List<String> skills = skills(command.skills());
		List<String> communityIds = ids(command.communityIds(), MAX_COMMUNITIES, "COMMUNITIES");
		List<String> groupIds = ids(command.groupIds(), MAX_GROUPS, "GROUPS");
		List<String> connectIds = ids(command.connectUserIds(), MAX_PEOPLE, "CONNECTIONS");

		mergeLikedTopics(userId, topics);
		mergeSkills(userId, skills);
		List<String> joinedCommunities = communityIds.stream().filter(id -> joinCommunity(userId, id)).toList();
		List<String> joinedGroups = groupIds.stream().filter(id -> joinOpenGroup(userId, id)).toList();
		List<String> requested = connectIds.stream().filter(id -> requestConnection(userId, id)).toList();
		markCompleted(userId);
		return new CompleteResult(true, joinedCommunities, joinedGroups, requested);
	}

	public CompleteResult skip(String userId) {
		markCompleted(userId);
		return new CompleteResult(true, List.of(), List.of(), List.of());
	}

	private void markCompleted(String userId) {
		UserProfile profile = profiles.findById(userId)
			.orElseThrow(() -> new ApiException(404, "USER_NOT_FOUND", "Usuario nao encontrado."));
		if (!profile.onboardingCompleted()) {
			profile.completeOnboarding(clock.instant());
			profiles.save(profile);
		}
	}

	/** Adds to "O que quero ver" keeping what was there; a topic the person blocked is a 409, as in the settings. */
	private void mergeLikedTopics(String userId, List<String> topics) {
		if (topics.isEmpty()) {
			return;
		}
		var current = personalization.of(userId);
		LinkedHashSet<String> liked = new LinkedHashSet<>(current.likedTopics());
		liked.addAll(topics);
		if (liked.size() == current.likedTopics().size()) {
			return;
		}
		personalization.update(userId, new ArrayList<>(liked), current.blockedTopics(), current.disabledModules(),
			null, null, null);
	}

	/** Adds skills through the profile update (same validation), keeping the existing ones. */
	private void mergeSkills(String userId, List<String> skills) {
		if (skills.isEmpty()) {
			return;
		}
		UserProfile profile = profiles.findById(userId)
			.orElseThrow(() -> new ApiException(404, "USER_NOT_FOUND", "Usuario nao encontrado."));
		var known = profile.skills().stream().map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
		List<String> merged = new ArrayList<>(profile.skills());
		skills.stream().filter(s -> !known.contains(s.toLowerCase(Locale.ROOT))).forEach(merged::add);
		if (merged.size() == profile.skills().size()) {
			return;
		}
		profileService.update(new UpdateProfileCommand(userId, null, null, null, null, null, null, null, null, null,
			null, merged, null, null, null, null, null, null, null, null, null, null, null, null, null, null));
	}

	private boolean joinCommunity(String userId, String communityId) {
		if (communities.isMember(communityId, userId)) {
			return true;
		}
		try {
			communities.join(communityId, userId);
			return true;
		}
		catch (ApiException skipped) {
			return false;
		}
	}

	/** Only groups with open entry: a group that needs approval is never joined (nor requested) from here. */
	private boolean joinOpenGroup(String userId, String groupId) {
		try {
			Group group = groups.resolve(groupId);
			if (!group.id().equals(groupId) || group.joinMode() != GroupJoinMode.OPEN) {
				return false;
			}
			if (groups.roleOf(groupId, userId) != null) {
				return true;
			}
			groups.join(groupId, userId);
			return true;
		}
		catch (ApiException skipped) {
			return false;
		}
	}

	/** Same path as the manual request: self, blocked and unknown accounts are skipped; repeats are no-ops. */
	private boolean requestConnection(String userId, String targetId) {
		if (targetId.equals(userId)) {
			return false;
		}
		try {
			network.requestConnection(userId, targetId);
			return true;
		}
		catch (ApiException skipped) {
			return false;
		}
	}

	private static List<String> topics(List<String> raw) {
		LinkedHashSet<String> topics = new LinkedHashSet<>();
		for (String value : raw == null ? List.<String>of() : raw) {
			String topic = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
			if (topic.isEmpty()) {
				continue;
			}
			if (!ContentTaxonomy.TOPIC_SET.contains(topic)) {
				throw new ApiException(400, "INVALID_ASSUNTO", "Valor desconhecido para assunto: " + topic);
			}
			topics.add(topic);
		}
		return List.copyOf(topics);
	}

	private static List<String> skills(List<String> raw) {
		LinkedHashSet<String> skills = new LinkedHashSet<>();
		for (String value : raw == null ? List.<String>of() : raw) {
			if (!StringUtils.hasText(value)) {
				continue;
			}
			String skill = value.trim();
			if (skill.length() > MAX_SKILL_LENGTH) {
				throw new ApiException(400, "SKILL_TOO_LONG",
					"Cada habilidade pode ter ate " + MAX_SKILL_LENGTH + " caracteres.");
			}
			skills.add(skill);
		}
		if (skills.size() > MAX_SKILLS) {
			throw new ApiException(400, "TOO_MANY_SKILLS", "Escolha ate " + MAX_SKILLS + " habilidades.");
		}
		return List.copyOf(skills);
	}

	private static List<String> ids(List<String> raw, int max, String kind) {
		List<String> ids = (raw == null ? List.<String>of() : raw).stream()
			.filter(StringUtils::hasText).map(String::trim).distinct().toList();
		if (ids.size() > max) {
			throw new ApiException(400, "TOO_MANY_" + kind, "Escolha ate " + max + " itens de cada vez.");
		}
		return ids;
	}

	private static CommunityCard card(CommunityCandidate c) {
		return new CommunityCard(c.id(), c.name(), shorten(c.description()), c.topic(), c.avatar(), c.memberCount());
	}

	private static GroupCard card(GroupCandidate g) {
		return new GroupCard(g.id(), g.slug(), g.name(), shorten(g.description()), g.topic(), g.avatar(),
			g.memberCount());
	}

	private static String shorten(String text) {
		if (text == null || text.length() <= MAX_DESCRIPTION) {
			return text;
		}
		return text.substring(0, MAX_DESCRIPTION - 3).stripTrailing() + "...";
	}
}
