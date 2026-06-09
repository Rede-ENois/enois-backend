package com.socialcoisa.rede.identity.adapters.out.oauth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@ConfigurationProperties(prefix = "rede.oauth")
public class OAuthProperties {

	private GoogleConfig google = new GoogleConfig();
	private AppleConfig apple = new AppleConfig();
	private String frontendRedirectUrl = "";

	public GoogleConfig getGoogle() { return google; }
	public void setGoogle(GoogleConfig google) { this.google = google; }

	public AppleConfig getApple() { return apple; }
	public void setApple(AppleConfig apple) { this.apple = apple; }

	public String getFrontendRedirectUrl() { return frontendRedirectUrl; }
	public void setFrontendRedirectUrl(String frontendRedirectUrl) { this.frontendRedirectUrl = frontendRedirectUrl; }

	public boolean isGoogleConfigured() {
		return StringUtils.hasText(google.clientId);
	}

	public boolean isAppleConfigured() {
		return StringUtils.hasText(apple.clientId);
	}

	public static class GoogleConfig {
		private String clientId = "";
		private String clientSecret = "";
		private String redirectUri = "";

		public String getClientId() { return clientId; }
		public void setClientId(String clientId) { this.clientId = clientId; }

		public String getClientSecret() { return clientSecret; }
		public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }

		public String getRedirectUri() { return redirectUri; }
		public void setRedirectUri(String redirectUri) { this.redirectUri = redirectUri; }
	}

	public static class AppleConfig {
		private String clientId = "";
		private String teamId = "";
		private String keyId = "";
		private String privateKey = "";
		private String redirectUri = "";

		public String getClientId() { return clientId; }
		public void setClientId(String clientId) { this.clientId = clientId; }

		public String getTeamId() { return teamId; }
		public void setTeamId(String teamId) { this.teamId = teamId; }

		public String getKeyId() { return keyId; }
		public void setKeyId(String keyId) { this.keyId = keyId; }

		public String getPrivateKey() { return privateKey; }
		public void setPrivateKey(String privateKey) { this.privateKey = privateKey; }

		public String getRedirectUri() { return redirectUri; }
		public void setRedirectUri(String redirectUri) { this.redirectUri = redirectUri; }
	}
}
