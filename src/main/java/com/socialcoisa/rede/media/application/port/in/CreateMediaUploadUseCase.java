package com.socialcoisa.rede.media.application.port.in;

import com.socialcoisa.rede.media.application.CreateMediaUploadCommand;
import com.socialcoisa.rede.media.application.MediaUploadView;

public interface CreateMediaUploadUseCase {

	MediaUploadView createUpload(CreateMediaUploadCommand command);
}
