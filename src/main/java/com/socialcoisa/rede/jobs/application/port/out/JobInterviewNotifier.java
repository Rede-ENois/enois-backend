package com.socialcoisa.rede.jobs.application.port.out;

import com.socialcoisa.rede.jobs.domain.Job;
import com.socialcoisa.rede.jobs.domain.JobInterview;

/**
 * Avisa o CANDIDATO que a entrevista dele mudou (marcada, reagendada ou cancelada).
 *
 * <p>Porta própria em vez de sobrecarregar {@link JobApplicationStatusNotifier}: o estado da
 * candidatura não muda quando a entrevista muda, e o texto é outro.
 */
public interface JobInterviewNotifier {

	void notifyChange(Job job, JobInterview interview, String candidateId);

	void notifyCancelled(Job job, JobInterview interview, String candidateId);
}
