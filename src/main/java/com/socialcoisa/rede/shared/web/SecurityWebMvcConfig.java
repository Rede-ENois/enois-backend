package com.socialcoisa.rede.shared.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registra as travas de autorização que valem por caminho, não por controller. */
@Configuration
class SecurityWebMvcConfig implements WebMvcConfigurer {

	private final AdminRouteInterceptor adminRouteInterceptor;
	private final WriteRouteInterceptor writeRouteInterceptor;

	SecurityWebMvcConfig(AdminRouteInterceptor adminRouteInterceptor,
			WriteRouteInterceptor writeRouteInterceptor) {
		this.adminRouteInterceptor = adminRouteInterceptor;
		this.writeRouteInterceptor = writeRouteInterceptor;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(adminRouteInterceptor).addPathPatterns("/admin/**", "/moderation/**");
		registry.addInterceptor(writeRouteInterceptor).addPathPatterns("/**");
	}
}
