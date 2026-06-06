package com.socialcoisa.rede.feed.application;

import java.util.List;

import com.socialcoisa.rede.feed.domain.PostType;
import com.socialcoisa.rede.feed.domain.UserId;

public record PublishPostCommand(UserId authorId, PostType type, String content, List<String> tags,
		List<String> mediaIds) {
}
