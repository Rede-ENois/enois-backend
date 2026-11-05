package com.socialcoisa.rede.media.application.port.in;

import java.util.Collection;
import java.util.Map;

import com.socialcoisa.rede.media.application.MediaSummaryView;

public interface GetMediaSummariesUseCase {

	/**
	 * The attachments a reader may still see (not removed), by id, in ONE query: list reads call it
	 * once per page with every media id of the page.
	 */
	Map<String, MediaSummaryView> visibleByIds(Collection<String> mediaIds);
}
