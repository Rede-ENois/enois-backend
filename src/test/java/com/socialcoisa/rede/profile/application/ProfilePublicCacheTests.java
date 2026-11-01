package com.socialcoisa.rede.profile.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.socialcoisa.rede.media.application.LazyUrlMigrationService;
import com.socialcoisa.rede.profile.application.port.out.OfficialBadgeReader;
import com.socialcoisa.rede.profile.application.port.out.SocialPostCountReader;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;
import com.socialcoisa.rede.shared.application.CacheConfig;
import com.socialcoisa.rede.shared.application.CacheInvalidator;
import com.socialcoisa.rede.shared.sanctions.SanctionGateReader;
import com.socialcoisa.rede.shared.security.ActionThrottle;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * O perfil publico tem cache com invalidação cirúrgica.
 *
 * <p>Conta o que importa para o banco: chamada ao repositorio. Leitura repetida do mesmo perfil
 * nao repete a consulta; edição aparece na leitura seguinte; e a edição de uma pessoa nao derruba
 * a entrada de outro alguem.</p>
 */
class ProfilePublicCacheTests {

	private static final Instant NOW = Instant.parse("2026-11-01T12:00:00Z");

	private UserProfileRepository repository;
	private AnnotationConfigApplicationContext context;
	private ProfileService profiles;

	@Configuration(proxyBeanMethods = false)
	// CGLIB, como em execucao (o Boot liga proxyTargetClass); com o padrao o bean vira proxy JDK e
	// nao responde pelo tipo concreto, que e o que o controller injeta.
	@EnableCaching(proxyTargetClass = true)
	static class TestCacheConfig {

		@Bean
		CacheManager cacheManager() {
			SimpleCacheManager manager = new SimpleCacheManager();
			manager.setCaches(List.of(
				new CaffeineCache(CacheConfig.PUBLIC_PROFILES,
					Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(5)).maximumSize(100).build()),
				new CaffeineCache(CacheConfig.PUBLIC_RANKING,
					Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(5)).maximumSize(100).build())));
			return manager;
		}
	}

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		repository = mock(UserProfileRepository.class);
		when(repository.findByUsername(any())).thenReturn(Optional.empty());
		when(repository.findById(any())).thenReturn(Optional.empty());

		ReputationService reputation = mock(ReputationService.class);
		when(reputation.countsFor(any())).thenReturn(new ReputationService.Counts(0, 0, 0, true, null));

		SocialPostCountReader postCounts = mock(SocialPostCountReader.class);
		when(postCounts.countByUser(any())).thenReturn(0);

		SanctionGateReader sanctionGate = mock(SanctionGateReader.class);
		when(sanctionGate.banned(any())).thenReturn(false);

		ObjectProvider<LazyUrlMigrationService> lazyMedia = mock(ObjectProvider.class);

		AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
		ctx.register(TestCacheConfig.class);
		ctx.registerBean(CacheInvalidator.class, () -> new CacheInvalidator(ctx.getBean(CacheManager.class)));
		ctx.registerBean(ProfileService.class, () -> new ProfileService(repository, reputation, lazyMedia,
			mock(com.socialcoisa.rede.age.application.port.out.ParentalLinkRepository.class), postCounts,
			sanctionGate, mock(OfficialBadgeReader.class), new ActionThrottle(),
			ctx.getBean(CacheInvalidator.class), 0, Clock.fixed(NOW, ZoneOffset.UTC)));
		ctx.refresh();
		context = ctx;
		profiles = ctx.getBean(ProfileService.class);
	}

	@AfterEach
	void tearDown() {
		context.close();
	}

	@Test
	void theSamePublicProfileIsNotRebuiltOnEveryRead() {
		when(repository.findByUsername("mel")).thenReturn(Optional.of(perfil("u1", "mel", "Mel")));

		UserProfileView first = profiles.getByUsername("mel");
		// A chave normaliza o nome: `/users/Mel` e `/users/mel` sao a mesma leitura.
		UserProfileView second = profiles.getByUsername("Mel");

		assertThat(second.name()).isEqualTo(first.name());
		verify(repository, times(1)).findByUsername(any());
	}

	@Test
	void editingTheProfileShowsUpOnTheNextRead() {
		UserProfile mel = perfil("u1", "mel", "Mel");
		when(repository.findById("u1")).thenReturn(Optional.of(mel));
		when(repository.findByUsername("mel")).thenReturn(Optional.of(mel));
		when(repository.save(any())).thenReturn(mel);

		profiles.getByUsername("mel");
		profiles.update(comando("u1", "Mel Nome Novo", null));

		assertThat(profiles.getByUsername("mel").name()).isEqualTo("Mel Nome Novo");
		verify(repository, times(2)).findByUsername("mel");
	}

	@Test
	void editingOneProfileKeepsEverybodyElsesEntry() {
		UserProfile mel = perfil("u1", "mel", "Mel");
		UserProfile ana = perfil("u2", "ana", "Ana");
		when(repository.findById("u1")).thenReturn(Optional.of(mel));
		when(repository.findByUsername("mel")).thenReturn(Optional.of(mel));
		when(repository.findByUsername("ana")).thenReturn(Optional.of(ana));
		when(repository.save(any())).thenReturn(mel);

		profiles.getByUsername("mel");
		profiles.getByUsername("ana");

		profiles.update(comando("u1", "Mel Editada", null));

		// A entrada de quem NAO editou continua valida: uma edicao nao custa a leitura de todo mundo.
		assertThat(profiles.getByUsername("mel").name()).isEqualTo("Mel Editada");
		profiles.getByUsername("ana");
		verify(repository, times(1)).findByUsername("ana");
		verify(repository, times(2)).findByUsername("mel");
	}

	@Test
	void renamingTheUserDropsTheEntryUnderTheOldNameToo() {
		UserProfile mel = perfil("u1", "mel", "Mel");
		when(repository.findById("u1")).thenReturn(Optional.of(mel));
		when(repository.findByUsername("mel")).thenReturn(Optional.of(mel));
		when(repository.save(any())).thenReturn(mel);

		profiles.getByUsername("mel");
		profiles.update(comando("u1", "Mel", "mel-nova"));

		// O nome antigo nao pode continuar servindo o perfil de antes da troca.
		when(repository.findByUsername("mel")).thenReturn(Optional.empty());
		assertThatThrownBy(() -> profiles.getByUsername("mel")).isInstanceOf(ApiException.class);
		verify(repository, times(2)).findByUsername("mel");
	}

	private static UserProfile perfil(String id, String username, String name) {
		return UserProfile.create(id, username, "usuario-" + id + "@exemplo.invalid", name, "headline", NOW);
	}

	private static UpdateProfileCommand comando(String userId, String name, String username) {
		return new UpdateProfileCommand(userId, name, null, null, null, null, null, null, null, null, null,
			List.of(), List.of(), null, null, List.of(), null, null, username, null, null, null, null, null, null,
			null);
	}
}