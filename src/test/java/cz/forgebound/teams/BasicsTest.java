package cz.forgebound.teams;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BasicsTest {

    @Test
    void namesAndTags() {
        assertTrue(TeamStore.NAME.matcher("Forge_Lords").matches());
        assertFalse(TeamStore.NAME.matcher("ab").matches());
        assertFalse(TeamStore.NAME.matcher("way_too_long_team_name").matches());
        assertFalse(TeamStore.NAME.matcher("bad name").matches());
        assertTrue(TeamStore.TAG.matcher("FL").matches());
        assertTrue(TeamStore.TAG.matcher("Abc1").matches());
        assertFalse(TeamStore.TAG.matcher("A").matches());
        assertFalse(TeamStore.TAG.matcher("ABCDE").matches());
    }

    @Test
    void versions() {
        assertEquals("1.2.3", Updater.version("v1.2.3-build.7"));
        assertTrue(Updater.compare("1.0.10", "1.0.9") > 0);
        assertEquals(0, Updater.compare("v1.0.0-build.1", "1.0.0"));
    }
}
