package fixture.bad.logging.infrastructure;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class UsesCommonsLogging {
    private static final Log LOG = LogFactory.getLog(UsesCommonsLogging.class);

    void log() {
        LOG.info("jcl");
    }
}
