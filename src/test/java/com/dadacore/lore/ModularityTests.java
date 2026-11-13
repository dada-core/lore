package com.dadacore.lore;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Each module only uses the public types of the others (those of its base package), and no
 * cycle exists between modules.
 */
class ModularityTests {

    @Test
    void modulesRespectTheirBoundaries() {
        ApplicationModules.of(LoreApplication.class).verify();
    }
}
