package com.socialcoisa.rede.feed.adapters.in.web;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.feed.application.AcceptedAnswerView;
import com.socialcoisa.rede.feed.application.QuestionAnswerService;
import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

/** Resposta aceita das duvidas. Aceitar e trocar sao idempotentes por natureza (gravam o estado). */
@RestController
class QuestionAnswerController {

	record AcceptAnswerRequest(String commentId) {
	}

	private final QuestionAnswerService questionAnswers;
	private final CurrentUserResolver currentUserResolver;

	QuestionAnswerController(QuestionAnswerService questionAnswers, CurrentUserResolver currentUserResolver) {
		this.questionAnswers = questionAnswers;
		this.currentUserResolver = currentUserResolver;
	}

	@PostMapping("/feed/posts/{postId}/accepted-answer")
	AcceptedAnswerView accept(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String postId, @RequestBody AcceptAnswerRequest request) {
		return questionAnswers.accept(new PostId(postId), currentUserResolver.resolve(sessionToken).id(),
			request.commentId());
	}

	@DeleteMapping("/feed/posts/{postId}/accepted-answer")
	AcceptedAnswerView unaccept(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String postId) {
		return questionAnswers.unaccept(new PostId(postId), currentUserResolver.resolve(sessionToken).id());
	}
}
