package com.socialcoisa.rede.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.socialcoisa.rede.calendar.application.CalendarAvailabilityService;
import com.socialcoisa.rede.calendar.application.port.out.CalendarAvailabilityRepository;
import com.socialcoisa.rede.calendar.domain.CalendarAvailabilityWindow;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Disponibilidade semanal contra o banco de verdade.
 *
 * <p>O que este teste tranca: a semana é substituída por inteiro (o que não veio de novo deixa de
 * existir), janelas que se cruzam no mesmo dia são recusadas sem perder o que já estava gravado,
 * encostar no fim não é sobrepor (meio-aberto), o dia é ISO-8601 de 1 a 7, e a semana de uma pessoa
 * nunca contamina a de outra.
 */
@SpringBootTest(properties = "spring.task.scheduling.enabled=false")
class CalendarAvailabilityServiceTests {

	private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");

	@Autowired CalendarAvailabilityService availability;
	@Autowired CalendarAvailabilityRepository windows;

	@Test
	void theWeekIsSavedSortedByDayAndStart() {
		String person = user();
		availability.replaceWeekly(person, List.of(window(3, "14:00", "18:00"), window(1, "09:00", "12:00"),
			window(3, "09:00", "12:00")));

		List<CalendarAvailabilityWindow> week = availability.weeklyOf(person);
		assertThat(week).extracting(CalendarAvailabilityWindow::dayOfWeek)
			.containsExactly(1, 3, 3);
		assertThat(week).extracting(w -> w.startsAt().toString()).containsExactly("09:00", "09:00", "14:00");
	}

	@Test
	void replacingRemovesWhatWasNotSentAgain() {
		String person = user();
		availability.replaceWeekly(person, List.of(window(1, "09:00", "12:00"), window(2, "09:00", "12:00")));

		availability.replaceWeekly(person, List.of(window(1, "09:00", "12:00")));

		assertThat(availability.weeklyOf(person)).hasSize(1);
		assertThat(availability.weeklyOf(person).get(0).dayOfWeek()).isEqualTo(1);
	}

	@Test
	void anEmptyListClearsTheWholeWeek() {
		String person = user();
		availability.replaceWeekly(person, List.of(window(5, "20:00", "22:00")));

		availability.replaceWeekly(person, List.of());

		assertThat(availability.weeklyOf(person)).isEmpty();
		assertThat(windows.findByUser(person)).isEmpty();
	}

	@Test
	void overlappingWindowsOnTheSameDayAreRejected() {
		String person = user();
		availability.replaceWeekly(person, List.of(window(4, "09:00", "12:00")));

		assertThatThrownBy(
			() -> availability.replaceWeekly(person, List.of(window(4, "09:00", "12:00"),
				window(4, "11:00", "13:00"))))
				.isInstanceOfSatisfying(ApiException.class, error -> {
					assertThat(error.status()).isEqualTo(400);
					assertThat(error.code()).isEqualTo("INVALID_AVAILABILITY");
				});
		// A semana anterior continua inteira: nada foi gravado pela metade.
		assertThat(availability.weeklyOf(person)).hasSize(1);
	}

	@Test
	void touchingEdgesAreTwoWindowsNotAnOverlap() {
		String person = user();

		availability.replaceWeekly(person, List.of(window(2, "09:00", "12:00"), window(2, "12:00", "18:00")));

		assertThat(availability.weeklyOf(person)).hasSize(2);
	}

	@Test
	void theSameHoursOnDifferentDaysAreTwoWindows() {
		String person = user();

		availability.replaceWeekly(person, List.of(window(1, "09:00", "12:00"), window(4, "09:00", "12:00")));

		assertThat(availability.weeklyOf(person)).hasSize(2);
	}

	@Test
	void aDayOutsideTheIsoRangeIsRejected() {
		String person = user();

		assertThatThrownBy(() -> availability.replaceWeekly(person, List.of(window(0, "09:00", "12:00"))))
			.isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> availability.replaceWeekly(person, List.of(window(8, "09:00", "12:00"))))
			.isInstanceOf(ApiException.class);
	}

	@Test
	void anEndNotAfterTheStartIsRejected() {
		String person = user();

		assertThatThrownBy(() -> availability.replaceWeekly(person, List.of(window(6, "12:00", "12:00"))))
			.isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> availability.replaceWeekly(person, List.of(window(6, "14:00", "12:00"))))
			.isInstanceOf(ApiException.class);
		assertThat(availability.weeklyOf(person)).isEmpty();
	}

	@Test
	void onePersonsWeekNeverTouchesAnothers() {
		String person = user();
		String other = user();
		availability.replaceWeekly(person, List.of(window(1, "09:00", "12:00")));

		availability.replaceWeekly(other, List.of());

		assertThat(availability.weeklyOf(person)).hasSize(1);
	}

	private static CalendarAvailabilityService.WeeklyWindow window(int day, String startsAt, String endsAt) {
		return new CalendarAvailabilityService.WeeklyWindow(day, LocalTime.parse(startsAt),
			LocalTime.parse(endsAt), SAO_PAULO);
	}

	private static String user() {
		return "u_" + UUID.randomUUID().toString().replace("-", "");
	}

}