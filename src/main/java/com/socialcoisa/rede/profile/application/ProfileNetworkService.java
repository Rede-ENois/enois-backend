package com.socialcoisa.rede.profile.application;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.profile.application.port.in.ListProfileNetworkUseCase;
import com.socialcoisa.rede.profile.application.port.out.ProfileNetworkReader;
import com.socialcoisa.rede.profile.application.port.out.ProfileNetworkReader.NetworkPeer;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;
import com.socialcoisa.rede.shared.blocks.BlockedUsersReader;
import com.socialcoisa.rede.shared.sanctions.SanctionGateReader;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Public connections/followers of a profile. Same visibility rule as the profile read: a banned or
 * deleted account is not found, and it is not listed in anyone else's network either. A signed-in
 * reader also does not see the people they blocked (or who blocked them).
 */
@Service
public class ProfileNetworkService implements ListProfileNetworkUseCase {

	/**
	 * Database pages read per request. People left out (banned, deleted, hidden) are skipped and the
	 * next page fills the gap; the cap keeps a graph full of left-out accounts from turning one
	 * request into a scan.
	 */
	static final int MAX_PAGES = 5;

	private final UserProfileRepository profiles;
	private final ProfileNetworkReader network;
	private final SanctionGateReader sanctionGate;
	private final BlockedUsersReader blocks;

	public ProfileNetworkService(UserProfileRepository profiles, ProfileNetworkReader network,
			SanctionGateReader sanctionGate, BlockedUsersReader blocks) {
		this.profiles = profiles;
		this.network = network;
		this.sanctionGate = sanctionGate;
		this.blocks = blocks;
	}

	@Override
	@Transactional(readOnly = true)
	public List<ProfileNetworkUserView> list(String username, boolean followersOnly, String viewerId, int limit) {
		int wanted = Math.clamp(limit, 1, MAX_LIMIT);
		Set<String> hidden = blocks.hiddenIdsFor(viewerId);
		UserProfile target = profiles.findByUsername(username)
			.filter(profile -> !profile.removed())
			.filter(profile -> !hidden.contains(profile.id()))
			.filter(profile -> !sanctionGate.banned(profile.id()))
			.orElseThrow(() -> new ApiException(404, "USER_NOT_FOUND", "Usuario nao encontrado."));
		List<ProfileNetworkUserView> result = new ArrayList<>(wanted);
		Set<String> seen = new HashSet<>();
		Long before = null;
		for (int page = 0; page < MAX_PAGES && result.size() < wanted; page++) {
			List<NetworkPeer> peers = network.acceptedPeers(target.id(), followersOnly, before, wanted);
			if (peers.isEmpty()) {
				break;
			}
			before = peers.getLast().connectionId();
			List<String> candidates = peers.stream()
				.map(NetworkPeer::peerId)
				.filter(seen::add)
				.filter(id -> !hidden.contains(id))
				.toList();
			appendVisible(candidates, wanted, result);
			if (peers.size() < wanted) {
				break;
			}
		}
		return result;
	}

	private void appendVisible(List<String> ids, int wanted, List<ProfileNetworkUserView> result) {
		if (ids.isEmpty()) {
			return;
		}
		Set<String> banned = sanctionGate.bannedAmong(ids);
		Map<String, UserProfile> byId = profiles.findAllByIds(ids).stream()
			.collect(Collectors.toMap(UserProfile::id, Function.identity()));
		for (String id : ids) {
			UserProfile profile = byId.get(id);
			if (result.size() >= wanted) {
				return;
			}
			if (profile != null && !profile.removed() && !banned.contains(id)) {
				result.add(ProfileNetworkUserView.from(profile));
			}
		}
	}
}
