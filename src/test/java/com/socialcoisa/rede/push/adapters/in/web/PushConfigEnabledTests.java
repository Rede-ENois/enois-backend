package com.socialcoisa.rede.push.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.spec.ECGenParameterSpec;

import org.bouncycastle.jce.interfaces.ECPrivateKey;
import org.bouncycastle.jce.interfaces.ECPublicKey;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import nl.martijndwars.webpush.Base64Encoder;
import nl.martijndwars.webpush.Utils;

/**
 * O contrato do {@code GET /push/config} quando as chaves VAPID estão configuradas: o app só oferece
 * "ligar avisos" se a resposta disser {@code enabled: true} e trouxer a chave pública com que o
 * navegador se inscreve.
 *
 * O par de chaves deste teste é gerado a cada execução e vive só aqui — não é chave de ambiente
 * nenhum.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PushConfigEnabledTests {

	private static final KeyPair GENERATED = generateKeyPair();

	private static final String PUBLIC_KEY = publicKeyOf(GENERATED);

	private static final String PRIVATE_KEY = privateKeyOf(GENERATED);

	@DynamicPropertySource
	static void vapid(DynamicPropertyRegistry registry) {
		registry.add("rede.push.vapid.public-key", () -> PUBLIC_KEY);
		registry.add("rede.push.vapid.private-key", () -> PRIVATE_KEY);
		registry.add("rede.push.vapid.subject", () -> "mailto:teste@enois.test");
	}

	@Autowired
	private MockMvc mockMvc;

	@Test
	void configAdvertisesTheFeatureAndThePublicKey() throws Exception {
		mockMvc.perform(get("/push/config"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.enabled").value(true))
			.andExpect(jsonPath("$.publicKey").value(PUBLIC_KEY));
	}

	@Test
	void theAdvertisedKeyIsTheVapidOneAndNeverThePrivateKey() throws Exception {
		String body = mockMvc.perform(get("/push/config")).andReturn().getResponse().getContentAsString();
		if (body.contains(PRIVATE_KEY)) {
			throw new AssertionError("a chave privada VAPID vazou na resposta de /push/config");
		}
	}

	private static KeyPair generateKeyPair() {
		if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
			Security.addProvider(new BouncyCastleProvider());
		}
		try {
			KeyPairGenerator generator = KeyPairGenerator.getInstance("ECDH", BouncyCastleProvider.PROVIDER_NAME);
			generator.initialize(new ECGenParameterSpec("secp256r1"));
			return generator.generateKeyPair();
		}
		catch (Exception exception) {
			throw new IllegalStateException("nao foi possivel gerar o par de chaves do teste", exception);
		}
	}

	private static String publicKeyOf(KeyPair pair) {
		return Base64Encoder.encodeUrlWithoutPadding(Utils.encode((ECPublicKey) pair.getPublic()));
	}

	private static String privateKeyOf(KeyPair pair) {
		return Base64Encoder.encodeUrlWithoutPadding(Utils.encode((ECPrivateKey) pair.getPrivate()));
	}
}