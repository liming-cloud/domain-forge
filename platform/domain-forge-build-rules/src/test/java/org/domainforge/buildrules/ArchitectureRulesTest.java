package org.domainforge.buildrules;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 规则的正反例：合法布局零违规，每类违规各有一个必须被发现的反例。
 */
class ArchitectureRulesTest {
    private static List<String> check(String packageName) {
        return ArchitectureRules.check(new ClassFileImporter().importPackages(packageName));
    }

    private static void assertViolation(String packageName, String tag) {
        List<String> violations = check(packageName);
        assertTrue(
                violations.stream().anyMatch(item -> item.startsWith(tag)),
                () -> packageName + " 应报告 " + tag + "，实际：" + violations);
    }

    @Test
    void locatesContextRootAndLayer() {
        assertEquals(
                new ArchitectureRules.Location("org.domainforge.iam", "application"),
                ArchitectureRules.locate("org.domainforge.iam.application.command"));
        assertNull(ArchitectureRules.locate("org.domainforge.web.internal"));
    }

    @Test
    void legalLayoutPasses() {
        assertEquals(List.of(), check("fixture.good"));
    }

    @Test
    void componentMayUseOwnInternal() {
        assertEquals(List.of(), check("org.domainforge.fixture.comp"));
    }

    @Test
    void domainCannotUseNetworkClient() {
        assertViolation("fixture.bad.domainhttp", "[技术边界]");
    }

    @Test
    void applicationCannotUseInfrastructure() {
        assertViolation("fixture.bad.appinfra", "[依赖方向]");
    }

    @Test
    void applicationCannotUseJdbc() {
        assertViolation("fixture.bad.appjdbc", "[技术边界]");
    }

    @Test
    void interfacesCannotUseDataSource() {
        assertViolation("fixture.bad.entrysql", "[技术边界]");
    }

    @Test
    void nothingDependsOnBootstrap() {
        assertViolation("fixture.bad.tobootstrap", "[依赖方向]");
    }

    @Test
    void contextClassesMustHaveLayer() {
        assertViolation("fixture.bad.orphan", "[职责归属]");
    }

    @Test
    void otherComponentInternalIsHidden() {
        assertViolation("fixture.bad.internal", "[内部实现]");
    }

    @Test
    void sqlAnnotationRejected() {
        assertViolation("fixture.bad.sqlannotation", "[SQL 位置]");
    }

    @Test
    void rawWrapperSqlRejected() {
        assertViolation("fixture.bad.rawsql", "[SQL 位置]");
    }

    @Test
    void crossContextInternalsRejected() {
        assertViolation("fixture.bad.cross", "[跨上下文]");
    }
}
