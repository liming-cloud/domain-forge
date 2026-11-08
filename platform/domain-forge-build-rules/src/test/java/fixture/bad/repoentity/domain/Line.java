package fixture.bad.repoentity.domain;

import org.jmolecules.ddd.annotation.Entity;
import org.jmolecules.ddd.annotation.Identity;

@Entity
public class Line {
    @Identity
    long id;
}
