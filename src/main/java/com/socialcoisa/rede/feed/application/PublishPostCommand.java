package com.socialcoisa.rede.feed.application;

import java.util.List;

import com.socialcoisa.rede.feed.domain.PostType;
import com.socialcoisa.rede.feed.domain.UserId;

public record PublishPostCommand(UserId authorId, PostType type, String content, String pageHtml, String articleBody,
		List<String> tags,
		List<String> mediaIds, String communityId, List<String> mentionedUserIds, String topic, String channelId,
		boolean mediaSpoiler,
		/** Alternativas da enquete (POLL); ignorado nos outros tipos. */
		List<String> pollOptions) {

	/** Publicação com canal/assunto e sem enquete. */
	public PublishPostCommand(UserId authorId, PostType type, String content, String pageHtml, String articleBody,
			List<String> tags, List<String> mediaIds, String communityId, List<String> mentionedUserIds, String topic,
			String channelId, boolean mediaSpoiler) {
		this(authorId, type, content, pageHtml, articleBody, tags, mediaIds, communityId, mentionedUserIds, topic,
			channelId, mediaSpoiler, null);
	}

	/** Publicação sem canal (feed/comunidade) mas com assunto declarado. */
	public PublishPostCommand(UserId authorId, PostType type, String content, String pageHtml, String articleBody,
			List<String> tags, List<String> mediaIds, String communityId, List<String> mentionedUserIds, String topic) {
		this(authorId, type, content, pageHtml, articleBody, tags, mediaIds, communityId, mentionedUserIds, topic,
			null, false);
	}

	/** Publicação sem assunto declarado (compatibilidade: blog e agendado não trazem o campo). */
	public PublishPostCommand(UserId authorId, PostType type, String content, String pageHtml, String articleBody,
			List<String> tags,
			List<String> mediaIds, String communityId, List<String> mentionedUserIds) {
		this(authorId, type, content, pageHtml, articleBody, tags, mediaIds, communityId, mentionedUserIds, null, null,
			false);
	}
}
