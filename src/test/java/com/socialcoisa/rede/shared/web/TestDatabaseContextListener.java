package com.socialcoisa.rede.shared.web;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;

/** Reassocia o helper ao contexto atual, inclusive quando o Spring reutiliza um contexto em cache. */
public class TestDatabaseContextListener extends AbstractTestExecutionListener {

	@Override
	public void prepareTestInstance(TestContext testContext) {
		vincularBanco(testContext);
	}

	@Override
	public void beforeTestMethod(TestContext testContext) {
		vincularBanco(testContext);
	}

	private void vincularBanco(TestContext testContext) {
		testContext.getApplicationContext().getBeanProvider(JdbcTemplate.class).ifAvailable(TestDatabase::install);
	}
}
