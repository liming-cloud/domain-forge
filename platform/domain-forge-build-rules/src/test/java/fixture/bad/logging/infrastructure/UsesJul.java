package fixture.bad.logging.infrastructure;

import java.util.logging.Logger;

public class UsesJul {
    private static final Logger LOGGER = Logger.getLogger(UsesJul.class.getName());

    void log() {
        LOGGER.info("jul");
    }
}
