package com.socialcoisa.rede.feed.application.port.in;

import com.socialcoisa.rede.feed.application.CommentsPage;
import com.socialcoisa.rede.feed.application.GetCommentsQuery;

public interface GetCommentsUseCase {

	CommentsPage getComments(GetCommentsQuery query);
}
