package com.socialcoisa.rede.releasenotes.application.port.out;

import java.util.Optional;

import com.socialcoisa.rede.releasenotes.domain.ReleaseNote;

/** Persistence of the single current release notes. */
public interface ReleaseNoteRepository {

	Optional<ReleaseNote> findCurrent();

	void save(ReleaseNote releaseNote);

	void clear();
}
