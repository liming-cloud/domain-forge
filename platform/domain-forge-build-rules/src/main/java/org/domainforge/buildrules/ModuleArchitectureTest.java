package org.domainforge.buildrules;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 由 platform parent 通过 surefire dependenciesToScan 在每个 Java 模块执行，只检查本模块 target/classes 的生产类。
 */
class ModuleArchitectureTest {
    /**
     * 本模块生产类遵守平台架构规则；没有生产类时视为通过。
     */
    @Test
    void productionClassesFollowArchitectureRules() {
        Path base = Path.of(System.getProperty("basedir", System.getProperty("user.dir")));
        Path classes = base.resolve("target/classes");
        if (!Files.isDirectory(classes)) {
            return;
        }
        JavaClasses imported = new ClassFileImporter().importPath(classes);
        List<String> violations = ArchitectureRules.check(imported);
        assertTrue(
                violations.isEmpty(),
                () -> "架构规则违规 " + violations.size() + " 项：\n" + String.join("\n", violations));
    }
}
