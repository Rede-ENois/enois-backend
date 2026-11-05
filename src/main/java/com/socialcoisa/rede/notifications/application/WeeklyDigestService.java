package com.socialcoisa.rede.notifications.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import com.socialcoisa.rede.blog.application.BlogNewsletterService;
import com.socialcoisa.rede.events.domain.Event;
import com.socialcoisa.rede.identity.application.EmailService;
import com.socialcoisa.rede.notifications.application.port.out.WeeklyDigestRepository;
import com.socialcoisa.rede.notifications.domain.DigestItem;
import com.socialcoisa.rede.preferences.application.PersonalizationService;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;
import com.socialcoisa.rede.shared.security.SecretCipher;

/**
 * Weekly digest e-mail: once a week, a calm summary of what happened around the person in the last
 * seven days (posts in their communities and groups, jobs matching their skills, events they will
 * attend, how many notifications wait unread).
 *
 * <p>Rules: verified e-mail only, digest not turned off, at most one per ISO week (the delivery row
 * is claimed before sending, so a failed send is not retried that week), and only when there is
 * real content: at least {@value #MIN_ITEMS} items besides the unread count. An empty week records
 * nothing and sends nothing. Every e-mail carries a one-click opt-out (encrypted token) and the
 * List-Unsubscribe header. Modules the person turned off in personalization stay out.</p>
 */
@Service
public class WeeklyDigestService {

	private static final Logger LOG = LoggerFactory.getLogger(WeeklyDigestService.class);

	static final String EMAIL_KIND = "WEEKLY_DIGEST";
	private static final String TOKEN_PREFIX = "digest:";
	private static final int MIN_ITEMS = 2;
	private static final int MAX_POSTS = 5;
	private static final int MAX_JOBS = 3;
	private static final int MAX_EVENTS = 3;
	private static final int EXCERPT_CHARS = 160;
	private static final Duration WINDOW = Duration.ofDays(7);
	private static final DateTimeFormatter EVENT_DAY = DateTimeFormatter.ofPattern("EEEE, dd/MM",
		Locale.forLanguageTag("pt-BR"));

	public enum Outcome { SENT, FAILED, EMPTY, ALREADY_SENT, NOT_ELIGIBLE }

	private final WeeklyDigestRepository digests;
	private final NotificationPreferenceService preferences;
	private final PersonalizationService personalization;
	private final UserProfileRepository profiles;
	private final EmailService email;
	private final SecretCipher cipher;
	private final TemplateEngine templateEngine;
	private final Clock clock;
	private final ZoneId zone;
	private final boolean enabled;
	private final int batchSize;
	private final String frontendUrl;
	/** One digest run at a time, off the shared scheduler thread (SMTP is slow). */
	private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "weekly-digest");
		thread.setDaemon(true);
		return thread;
	});
	private final AtomicBoolean running = new AtomicBoolean();

	public WeeklyDigestService(WeeklyDigestRepository digests, NotificationPreferenceService preferences,
			PersonalizationService personalization, UserProfileRepository profiles, EmailService email,
			SecretCipher cipher, TemplateEngine templateEngine, Clock clock,
			@Value("${rede.digest.zone:America/Sao_Paulo}") String zone,
			@Value("${rede.digest.enabled:true}") boolean enabled,
			@Value("${rede.digest.batch-size:200}") int batchSize,
			@Value("${rede.frontend.url}") String frontendUrl) {
		this.digests = digests;
		this.preferences = preferences;
		this.personalization = personalization;
		this.profiles = profiles;
		this.email = email;
		this.cipher = cipher;
		this.templateEngine = templateEngine;
		this.clock = clock;
		this.zone = ZoneId.of(zone);
		this.enabled = enabled;
		this.batchSize = Math.max(1, batchSize);
		this.frontendUrl = frontendUrl.endsWith("/") ? frontendUrl.substring(0, frontendUrl.length() - 1)
			: frontendUrl;
	}

	@Scheduled(cron = "${rede.digest.cron:0 0 9 * * MON}", zone = "${rede.digest.zone:America/Sao_Paulo}")
	void scheduledRun() {
		if (!enabled) {
			return;
		}
		executor.execute(this::runWeekly);
	}

	@jakarta.annotation.PreDestroy
	void shutdown() {
		executor.shutdownNow();
	}

	/** Walks every eligible person in bounded pages; one failing recipient never stops the run. */
	public void runWeekly() {
		if (!running.compareAndSet(false, true)) {
			return;
		}
		try {
			if (!cipher.isEnabled()) {
				LOG.warn("weekly_digest skipped: REDE_SECURITY_ENCRYPTION_KEY missing (opt-out token)");
				return;
			}
			String weekKey = weekKey();
			String cursor = "";
			int sent = 0;
			int failed = 0;
			int empty = 0;
			List<String> page;
			do {
				page = digests.eligibleUserIds(weekKey, cursor, batchSize);
				for (String userId : page) {
					try {
						switch (sendTo(userId)) {
							case SENT -> sent++;
							case FAILED -> failed++;
							case EMPTY -> empty++;
							default -> { }
						}
					}
					catch (RuntimeException perRecipient) {
						failed++;
						LOG.warn("weekly_digest recipient_failed error={}", perRecipient.getClass().getSimpleName());
					}
				}
				if (!page.isEmpty()) {
					cursor = page.get(page.size() - 1);
				}
			}
			while (page.size() == batchSize);
			LOG.info("weekly_digest week={} sent={} failed={} empty={}", weekKey, sent, failed, empty);
		}
		finally {
			running.set(false);
		}
	}

	/** The whole rule for one person (also called directly by the tests). */
	public Outcome sendTo(String userId) {
		UserProfile profile = profiles.findById(userId).orElse(null);
		if (profile == null || !StringUtils.hasText(profile.email()) || !profile.emailVerified()
				|| !preferences.of(userId).weeklyDigest() || !cipher.isEnabled()) {
			return Outcome.NOT_ELIGIBLE;
		}
		Instant now = clock.instant();
		Instant since = now.minus(WINDOW);
		List<String> disabled = personalization.of(userId).disabledModules();

		List<DigestItem> posts = new ArrayList<>();
		if (!disabled.contains("COMMUNITIES")) {
			posts.addAll(digests.communityPosts(userId, since, MAX_POSTS).stream()
				.map(post -> new DigestItem(excerpt(post.title()), post.context(), post.path(), post.score()))
				.toList());
		}
		if (!disabled.contains("GROUPS")) {
			posts.addAll(digests.activeGroups(userId, since, MAX_POSTS));
		}
		posts = posts.stream().sorted(Comparator.comparingInt(DigestItem::score).reversed()).limit(MAX_POSTS).toList();
		List<DigestItem> jobs = disabled.contains("JOBS") ? List.of() : digests.matchingJobs(userId, since, MAX_JOBS);
		List<DigestItem> events = disabled.contains("EVENTS") ? List.of() : upcomingEvents(userId, now);

		if (posts.size() + jobs.size() + events.size() < MIN_ITEMS) {
			return Outcome.EMPTY;
		}
		if (!digests.claim(userId, weekKey(), now)) {
			return Outcome.ALREADY_SENT;
		}
		String unsubscribeLink = frontendUrl + "/api/notifications/digest/unsubscribe?token=" + unsubscribeToken(userId);
		Context context = new Context();
		context.setVariable("name", firstName(profile));
		context.setVariable("appUrl", frontendUrl);
		context.setVariable("unread", digests.unreadNotifications(userId, since));
		context.setVariable("posts", posts);
		context.setVariable("jobs", jobs);
		context.setVariable("events", events);
		context.setVariable("unsubscribeLink", unsubscribeLink);
		context.setVariable("currentYear", LocalDate.now(clock.withZone(zone)).getYear());
		boolean sent = email.sendHtml(profile.email(), "Sua semana no Énóis",
			templateEngine.process("email-weekly-digest", context), "Énóis", null, unsubscribeLink, EMAIL_KIND);
		return sent ? Outcome.SENT : Outcome.FAILED;
	}

	/** Same encrypted token as the blog newsletter, with its own prefix so the two never mix. */
	public String unsubscribeToken(String userId) {
		String standard = cipher.encrypt(TOKEN_PREFIX + userId);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(Base64.getDecoder().decode(standard));
	}

	/** One-click opt-out from the e-mail. Idempotent: a second click on a valid link still succeeds. */
	public boolean unsubscribeByToken(String token) {
		if (!StringUtils.hasText(token) || !cipher.isEnabled()) {
			return false;
		}
		String data;
		try {
			data = cipher.decrypt(Base64.getEncoder().encodeToString(Base64.getUrlDecoder().decode(token.trim())));
		}
		catch (RuntimeException invalidToken) {
			return false;
		}
		if (!data.startsWith(TOKEN_PREFIX) || data.length() == TOKEN_PREFIX.length()) {
			return false;
		}
		String userId = data.substring(TOKEN_PREFIX.length());
		if (profiles.findById(userId).isEmpty()) {
			return false;
		}
		preferences.update(userId, false, null, null, null, null, null);
		return true;
	}

	/** ISO week in the digest zone, e.g. "2026-W44". */
	String weekKey() {
		LocalDate today = LocalDate.now(clock.withZone(zone));
		return "%d-W%02d".formatted(today.get(IsoFields.WEEK_BASED_YEAR), today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
	}

	private List<DigestItem> upcomingEvents(String userId, Instant now) {
		LocalDate today = LocalDate.ofInstant(now, zone);
		LocalDate until = today.plusDays(7);
		record Dated(LocalDate day, WeeklyDigestRepository.AttendingEvent event) {
		}
		return digests.attendingEvents(userId).stream()
			.map(event -> new Dated(Event.parseDate(event.date()), event))
			.filter(dated -> dated.day() != null && !dated.day().isBefore(today) && dated.day().isBefore(until))
			.sorted(Comparator.comparing(Dated::day))
			.limit(MAX_EVENTS)
			.map(dated -> new DigestItem(dated.event().title(), when(dated.day(), dated.event().time()), "/eventos", 0))
			.toList();
	}

	private static String when(LocalDate day, String time) {
		String label = day.format(EVENT_DAY);
		return StringUtils.hasText(time) ? label + " às " + time.trim() : label;
	}

	private static String excerpt(String content) {
		String text = BlogNewsletterService.previewOf(content);
		return text.length() > EXCERPT_CHARS ? text.substring(0, EXCERPT_CHARS).trim() + "…" : text;
	}

	private static String firstName(UserProfile profile) {
		String name = profile.name() == null ? "" : profile.name().trim();
		int space = name.indexOf(' ');
		return space > 0 ? name.substring(0, space) : name;
	}
}
