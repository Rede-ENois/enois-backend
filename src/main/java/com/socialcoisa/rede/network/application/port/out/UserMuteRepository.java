package com.socialcoisa.rede.network.application.port.out;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.socialcoisa.rede.network.domain.UserMute;

public interface UserMuteRepository {

	void save(UserMute mute);

	void delete(String muterId, String mutedId);

	boolean exists(String muterId, String mutedId);

	/** O silenciamento que ainda vale entre os dois, se houver (um prazo vencido nao conta). */
	Optional<UserMute> findActive(String muterId, String mutedId);

	List<UserMute> findByMuterId(String muterId);

	/** Ids silenciados por este usuario. */
	Set<String> findMutedIds(String muterId);
}
