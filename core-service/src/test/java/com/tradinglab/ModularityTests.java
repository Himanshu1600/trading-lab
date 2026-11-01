package com.tradinglab;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTests {

	@Test
	void modulesRespectBoundaries() {
		ApplicationModules.of(CoreServiceApplication.class).verify();
	}

}
