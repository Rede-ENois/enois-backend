package com.socialcoisa.rede.profile.application.port.out;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.profile.domain.UserProfile;

public interface UserProfileRepository {

	UserProfile save(UserProfile profile);

	/**
	 * Writes only the name style columns. The `users` row is written by many flows, and saving the
	 * whole profile for a style pick rewrote (and raced) all of them.
	 */
	void updateNameStyle(String userId, String theme, String font, String color, java.time.Instant at);

	Optional<UserProfile> findById(String id);

	List<UserProfile> findAllByIds(Collection<String> ids);

	/**
	 * Existência + nome + @ de várias contas, em uma consulta e sem as coleções do perfil — o
	 * suficiente para as rotinas de triagem/limpeza (nome suspeito, conta excluída).
	 */
	List<AccountIdentity> identitiesOf(Collection<String> ids);

	Optional<UserProfile> findByUsername(String username);

	Optional<UserProfile> findByEmail(String email);

	boolean existsByUsername(String username);

	boolean existsByEmail(String email);

	int countConnections(String userId);

	int countFollowers(String userId);

	int countPostInteractionsReceived(String userId);

	int countCommunitiesJoined(String userId);

	int countEventsAttended(String userId);

	int countMentoringSessionsAsMentee(String userId);

	int countActiveReportsAgainst(String userId);

	/**
	 * Fatos CONFIRMADOS contra o usuário: denúncias julgadas procedentes (APPROVED) ou
	 * penalizadas automaticamente (AUTO_PENALIZED), deduplicadas por conteúdo denunciado.
	 * Pendente não é fato; descartada não existe.
	 */
	int countConfirmedReportFactsAgainst(String userId);

	/** Pessoas DISTINTAS (sem o próprio autor) que reagiram ou comentaram posts dele. */
	int countUniqueEngagers(String userId);

	/** Presenças em eventos: as ainda existentes + o agregado guardado antes da purga. */
	int countEventsAttendedTotal(String userId);

	/** Grava a reputação na coluna de ranking (a busca de perfis ordena por ela). */
	void updateReputation(String userId, int reputation);

	/** Ids em páginas estáveis, para o job de atualização do ranking. */
	List<String> findAllIds(int offset, int limit);

	/** O ranking público: os perfis que acabaram de chegar, mais recentes primeiro. */
	List<UserProfile> findNewestProfiles(int limit);

	/** Contas com papel administrativo (coluna imutável), para a gestão de admins. */
	List<UserProfile> findAdmins();

	List<UserProfile> searchByQuery(String query);
}
