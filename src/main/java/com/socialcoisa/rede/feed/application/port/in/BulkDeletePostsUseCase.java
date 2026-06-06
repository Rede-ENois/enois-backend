package com.socialcoisa.rede.feed.application.port.in;

import com.socialcoisa.rede.feed.application.BulkDeletePostsCommand;
import com.socialcoisa.rede.feed.application.BulkDeleteResult;

public interface BulkDeletePostsUseCase {

	BulkDeleteResult delete(BulkDeletePostsCommand command);
}
