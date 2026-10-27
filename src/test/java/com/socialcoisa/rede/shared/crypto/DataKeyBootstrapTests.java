package com.socialcoisa.rede.shared.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

/**
 * O bootstrap cria a primeira chave de dados e não cria outra a cada reinício.
 *
 * <p>A chave em claro nunca pode sair do processo: o teste confere que só o embrulho do cofre é
 * gravado e que o material tem 32 bytes.</p>
 */
class DataKeyBootstrapTests {

	private static final class MemoriaStore implements WrappedKeyStore {

		private final Map<String, String> keys = new HashMap<>();

		@Override
		public String activeKid() {
			if (keys.isEmpty()) {
				throw new CryptoException("KEY_NOT_CONFIGURED", "sem chave");
			}
			return keys.keySet().iterator().next();
		}

		@Override
		public Optional<String> wrappedByKid(String kid) {
			return Optional.ofNullable(keys.get(kid));
		}

		@Override
		public void save(String kid, String wrappedKey) {
			keys.put(kid, wrappedKey);
		}
	}

	@Test
	void criaUmaChaveQuandoNaoExisteNenhuma() {
		MemoriaStore store = new MemoriaStore();
		VaultTransitClient vault = mock(VaultTransitClient.class);
		when(vault.encrypt(any())).thenReturn("embrulhado-pelo-cofre");

		new DataKeyBootstrap(store, vault).ensureActiveKey();

		assertThat(store.activeKid()).startsWith("dek-");
		assertThat(store.wrappedByKid(store.activeKid())).contains("embrulhado-pelo-cofre");
	}

	@Test
	void embrulhaExatamente32Bytes() {
		MemoriaStore store = new MemoriaStore();
		VaultTransitClient vault = mock(VaultTransitClient.class);
		AtomicReference<byte[]> enviado = new AtomicReference<>();
		when(vault.encrypt(any())).thenAnswer(invocation -> {
			enviado.set(invocation.getArgument(0, byte[].class));
			return "embrulhado";
		});

		new DataKeyBootstrap(store, vault).ensureActiveKey();

		assertThat(enviado.get()).hasSize(32);
	}

	@Test
	void reinicioNaoCriaChaveNova() {
		MemoriaStore store = new MemoriaStore();
		store.save("dek-existente", "embrulho");
		VaultTransitClient vault = mock(VaultTransitClient.class);

		new DataKeyBootstrap(store, vault).ensureActiveKey();

		verify(vault, never()).encrypt(any());
	}

	@Test
	void falhaDoCofreNaoViraChaveSemEmbrulho() {
		MemoriaStore store = new MemoriaStore();
		VaultTransitClient vault = mock(VaultTransitClient.class);
		when(vault.encrypt(any())).thenThrow(new CryptoException("VAULT_UNAVAILABLE", "cofre fora"));

		assertThatThrownBy(() -> new DataKeyBootstrap(store, vault).ensureActiveKey())
			.isInstanceOfSatisfying(CryptoException.class,
				failure -> assertThat(failure.code()).isEqualTo("VAULT_UNAVAILABLE"));
	}
}
