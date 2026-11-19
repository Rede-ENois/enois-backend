package com.socialcoisa.rede.support.application.port.out;

import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.support.domain.SupportMessage;

public interface SupportMessageRepository {

	List<SupportMessage> findByRequestId(String requestId);

	Optional<SupportMessage> findByGithubCommentId(long commentId);

	Optional<SupportMessage> findById(String id);

	SupportMessage save(SupportMessage message);

	void deleteById(String id);
}