package com.socialcoisa.rede.preferences.adapters.out.feed;

import java.util.Collection;

import org.springframework.stereotype.Component;

import com.socialcoisa.rede.feed.application.port.out.ReaderPreferencesReader;
import com.socialcoisa.rede.preferences.application.PersonalizationService;

/** Fonte única das preferências: a mesma tabela que a UI lê e grava. */
@Component
public class ReaderPreferencesReaderAdapter implements ReaderPreferencesReader {

	private final PersonalizationService personalization;

	ReaderPreferencesReaderAdapter(PersonalizationService personalization) {
		this.personalization = personalization;
	}

	@Override
	public Collection<String> blockedTopics(String userId) {
		return personalization.of(userId).blockedTopics();
	}

	@Override
	public Collection<String> likedTopics(String userId) {
		return personalization.of(userId).likedTopics();
	}

	@Override
	public boolean hideReposts(String userId) {
		return personalization.of(userId).hideReposts();
	}
}
