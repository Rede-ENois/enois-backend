package com.socialcoisa.rede.messaging.application;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.socialcoisa.rede.network.application.port.out.ConnectionRepository;
import com.socialcoisa.rede.shared.age.AgePolicyReader;
import com.socialcoisa.rede.shared.blocks.BlockedUsersReader;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Who may reach whom in a conversation: blocks and the minor protection (ECA Digital).
 *
 * <p>The rules depend on the CURRENT state of the people involved, so they run when a conversation
 * is opened, when an existing one is reopened, and on every message. Checking them only at creation
 * let a block (or a connection removed from a minor) made later be ignored by an existing thread.
 */
@Component
public class ConversationReachPolicy {

	private final AgePolicyReader agePolicy;
	private final ConnectionRepository connections;
	private final BlockedUsersReader blockedUsers;

	public ConversationReachPolicy(AgePolicyReader agePolicy, ConnectionRepository connections,
			BlockedUsersReader blockedUsers) {
		this.agePolicy = agePolicy;
		this.connections = connections;
		this.blockedUsers = blockedUsers;
	}

	/**
	 * A conversation created from the generic endpoint (DIRECT or GROUP). One read per kind for the
	 * whole set (up to 100 people), never one per participant; the creator's hidden set covers blocks
	 * in both directions and the creator's own mutes.
	 */
	void requireMayOpen(String creatorId, List<String> others) {
		Set<String> creatorHidden = blockedUsers.hiddenIdsFor(creatorId);
		if (others.stream().anyMatch(creatorHidden::contains)) {
			throw blocked();
		}
		requireMayContactMinors(creatorId, others);
	}

	/** A one-to-one conversation opened (or reopened) by its own flow: application, hiring, proposal, DM. */
	void requireMayOpenPair(String kind, String actorId, String otherId) {
		requireMayReach(kind, actorId, List.of(otherId));
	}

	/** Every message, with the participants as they are now. */
	void requireMaySend(String kind, String senderId, Collection<String> recipientIds) {
		requireMayReach(kind, senderId, recipientIds);
	}

	private void requireMayReach(String kind, String actorId, Collection<String> recipientIds) {
		// A group is many people who did not all choose each other: a block between two members
		// hides their content from each other but does not silence the whole group.
		if (!"GROUP".equalsIgnoreCase(kind)) {
			for (String recipientId : recipientIds) {
				if (blockedUsers.blockedBetween(actorId, recipientId)) {
					throw blocked();
				}
			}
		}
		// JOB and SERVICE conversations are opened by the minor's own action (applying, hiring)
		// and keep working; every other person-to-person conversation, groups included, follows it.
		if (!"JOB".equalsIgnoreCase(kind) && !"SERVICE".equalsIgnoreCase(kind)) {
			requireMayContactMinors(actorId, recipientIds);
		}
	}

	/** ECA Digital: a minor is only reachable by someone with an ACCEPTED connection to them. */
	private void requireMayContactMinors(String actorId, Collection<String> recipientIds) {
		Set<String> minors = agePolicy.minorIds(recipientIds);
		if (!minors.isEmpty() && !connections.findAcceptedAmong(actorId, minors).containsAll(minors)) {
			throw new ApiException(403, "MINOR_DM_RESTRICTED",
				"Para proteger usuarios menores de idade, so e possivel conversar com eles apos uma conexao aceita.");
		}
	}

	private static ApiException blocked() {
		return new ApiException(403, "USER_BLOCKED", "Nao e possivel conversar com este usuario.");
	}
}
