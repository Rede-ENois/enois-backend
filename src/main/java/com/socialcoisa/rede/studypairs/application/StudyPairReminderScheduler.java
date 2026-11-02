package com.socialcoisa.rede.studypairs.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Weekly check-in reminder, Thursday 18:00 in Brasilia by default ({@code rede.study-pairs.reminder-cron};
 * "-" turns it off). Safe on several replicas: each reminder is claimed in study_pair_reminders.
 */
@Component
class StudyPairReminderScheduler {

	private static final Logger LOG = LoggerFactory.getLogger(StudyPairReminderScheduler.class);

	private final StudyPairService service;

	StudyPairReminderScheduler(StudyPairService service) {
		this.service = service;
	}

	@Scheduled(cron = "${rede.study-pairs.reminder-cron:0 0 18 * * THU}", zone = "America/Sao_Paulo")
	public void remind() {
		try {
			int sent = service.sendWeeklyReminders();
			LOG.info("study_pair_reminders_sent count={}", sent);
		}
		catch (RuntimeException failure) {
			LOG.warn("study_pair_reminders_failed error={}", failure.getMessage());
		}
	}
}
