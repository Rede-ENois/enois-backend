package com.socialcoisa.rede.media.application.port.in;

import java.util.List;

import com.socialcoisa.rede.feed.domain.UserId;

public interface ValidateMediaOwnershipUseCase {

	void validateOwnedBy(UserId ownerId, List<String> mediaIds);
}
