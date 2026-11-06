package com.socialcoisa.rede.profile.application.port.out;

import java.util.List;

/**
 * Accepted connections of a profile, one database page at a time. Lives in the profile module
 * because the public network list is a profile screen; the network module implements it, since
 * that is where the connection rows live.
 */
public interface ProfileNetworkReader {

	/**
	 * Accepted connections of {@code userId}, newest first. {@code followersOnly} keeps only the
	 * connections the others sent to this profile. {@code beforeConnectionId} is the keyset cursor
	 * (null for the first page).
	 */
	List<NetworkPeer> acceptedPeers(String userId, boolean followersOnly, Long beforeConnectionId, int limit);

	record NetworkPeer(long connectionId, String peerId) {
	}
}
