package com.socialcoisa.rede.feed.adapters.in.web;

import java.util.List;

import com.socialcoisa.rede.feed.domain.PostType;

public record PublishPostRequest(PostType type, String content, List<String> tags, List<String> mediaIds) {
}
