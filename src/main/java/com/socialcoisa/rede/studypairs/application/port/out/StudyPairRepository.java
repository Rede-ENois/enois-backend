package com.socialcoisa.rede.studypairs.application.port.out;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.studypairs.domain.PopularGoal;
import com.socialcoisa.rede.studypairs.domain.StudyCheckin;
import com.socialcoisa.rede.studypairs.domain.StudyPair;
import com.socialcoisa.rede.studypairs.domain.StudyPairRequest;

public interface StudyPairRepository {

	void saveRequest(StudyPairRequest request);

	Optional<StudyPairRequest> findOpenRequest(String userId);

	/** Open requests for this goal, oldest first (FIFO). */
	List<StudyPairRequest> findOpenByGoal(String goalKey, int limit);

	/** Open requests matching the term in the goal text or the topic, oldest first. */
	List<StudyPairRequest> searchOpenRequests(String term, int limit);

	/** Moves an OPEN request to {@code status}; false when it was no longer OPEN (claimed by someone else). */
	boolean closeOpenRequest(String requestId, StudyPairRequest.Status status);

	void savePair(StudyPair pair);

	Optional<StudyPair> findPair(String pairId);

	List<StudyPair> findActivePairs(String userId);

	List<StudyPair> findAllActivePairs();

	/** Ends an ACTIVE pair; false when it was already ended. */
	boolean endPair(String pairId, Instant endedAt);

	Optional<StudyCheckin> findCheckin(String pairId, String userId, String weekKey);

	void insertCheckin(StudyCheckin checkin);

	void updateCheckinNote(String pairId, String userId, String weekKey, String note, Instant updatedAt);

	List<StudyCheckin> findCheckins(String pairId, Collection<String> weekKeys);

	List<PopularGoal> popularOpenGoals(int limit);

	/** Claims this week's reminder for one member; false when another run already claimed it. */
	boolean claimReminder(String pairId, String userId, String weekKey, Instant at);
}
