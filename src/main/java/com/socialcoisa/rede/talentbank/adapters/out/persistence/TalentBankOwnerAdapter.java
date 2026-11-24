package com.socialcoisa.rede.talentbank.adapters.out.persistence;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;
import com.socialcoisa.rede.talentbank.application.port.out.TalentBankOwnerDirectory;

/**
 * Os donos das bancas lidos do módulo de perfil, que é quem tem a conta.
 *
 * <p>Só o que a vitrine precisa: como a pessoa se apresenta e se ela está no modo recrutador.
 * Nada da conta além disso — a banca é pública, a pessoa não.</p>
 */
@Repository
class TalentBankOwnerAdapter implements TalentBankOwnerDirectory {

	private final UserProfileRepository profiles;

	TalentBankOwnerAdapter(UserProfileRepository profiles) {
		this.profiles = profiles;
	}

	@Override
	public boolean recruiter(String userId) {
		if (userId == null || userId.isBlank()) {
			return false;
		}
		return profiles.findById(userId).map(UserProfile::recruiter).orElse(false);
	}

	@Override
	public Map<String, Owner> owners(Collection<String> ownerIds) {
		Map<String, Owner> result = new LinkedHashMap<>();
		if (ownerIds == null || ownerIds.isEmpty()) {
			return result;
		}
		List<UserProfile> found = profiles.findAllByIds(ownerIds);
		for (UserProfile profile : found) {
			result.put(profile.id(), new Owner(profile.id(), profile.displayName(), profile.username(),
				profile.avatar(), profile.headline(), profile.location(), profile.recruiter()));
		}
		return result;
	}
}
