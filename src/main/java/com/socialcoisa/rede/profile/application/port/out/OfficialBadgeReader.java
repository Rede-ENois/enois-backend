package com.socialcoisa.rede.profile.application.port.out;

import java.util.Collection;

/**
 * Official badges (shop and event badges, stored by catalog id) render as platform art, so only who
 * owns one may show it. Image URLs uploaded by the person are not official and always pass.
 */
public interface OfficialBadgeReader {

	/** True when every official badge id among {@code badges} is owned by the person. */
	boolean ownsAllOfficial(String userId, Collection<String> badges);
}
