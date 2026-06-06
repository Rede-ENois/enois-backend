package com.socialcoisa.rede.feed.application;

import com.socialcoisa.rede.feed.domain.Counters;

public record PostActionResult(String postId, String status, Counters counters) {
}
