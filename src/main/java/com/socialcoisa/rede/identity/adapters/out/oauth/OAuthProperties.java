package com.socialcoisa.rede.identity.adapters.out.oauth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@ConfigurationProperties(prefix = "rede.oauth")
public class OAuthProperties {

	private GoogleConfig google = new GoogleConfig();
	private AppleConfig apple = new AppleConfig();
	private LinkedInConfig linkedin = new LinkedInConfig();
	private GitHubConfig github = new GitHubConfig();
	private String frontendRedirectUrl = "";

	public GoogleConfig getGoogle() { return google; }
	public void setGoogle(GoogleConfig google) { this.google = google; }

	public AppleConfig getApple() { return apple; }
	public void setApple(AppleConfig apple) { this.apple = apple; }

	public LinkedInConfig getLinkedin() { return linkedin; }
	public void setLinkedin(LinkedInConfig linkedin) { this.linkedin = linkedin; }

	public GitHubConfig getGithub() { return github; }
	public void setGithub(GitHubConfig github) { this.github = github; }

	public boolean isLinkedinConfigured() {
		return StringUtils.hasText(linkedin.clientId);
	}

	public boolean isGithubConfigured() {
		return StringUtils.hasText(github.clientId);
	}

	public String getFrontendRedirectUrl() { return frontendRedirectUrl; }
	public void setFrontendRedirectUrl(String frontendRedirectUrl) { this.frontendRedirectUrl = frontendRedirectUrl; }

	/**
	 * URL de retorno sem barra final nem espaco sobrando.
	 *
	 * O callback e montado como {@code base + "/api/auth/oauth/..."}: se a variavel de ambiente
	 * vier com "/" no fim, o redirect sai com barra dupla e o Google recusa como
	 * redirect_uri_mismatch (aconteceu no QA em 23/10).
	 */
	private static String clean(String url) {
		return url == null ? "" : url.trim().replaceAll("/+$", "");
	}

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

		public String getRedirectUri() { return clean(redirectUri); }
		public void setRedirectUri(String redirectUri) { this.redirectUri = redirectUri; }
	}

	public static class LinkedInConfig {
		private String clientId = "";
		private String clientSecret = "";
		private String redirectUri = "";

		public String getClientId() { return clientId; }
		public void setClientId(String clientId) { this.clientId = clientId; }

		public String getClientSecret() { return clientSecret; }
		public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }

		public String getRedirectUri() { return clean(redirectUri); }
		public void setRedirectUri(String redirectUri) { this.redirectUri = redirectUri; }
	}

	public static class GitHubConfig {
		private String clientId = "";
		private String clientSecret = "";
		private String redirectUri = "";

		public String getClientId() { return clientId; }
		public void setClientId(String clientId) { this.clientId = clientId; }

		public String getClientSecret() { return clientSecret; }
		public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }

		public String getRedirectUri() { return clean(redirectUri); }
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

		public String getRedirectUri() { return clean(redirectUri); }
		public void setRedirectUri(String redirectUri) { this.redirectUri = redirectUri; }
	}
}
