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

    @Test
    void componentCycleRejected() {
        assertViolation("org.domainforge.fixture.cyc", "[组件环]");
    }

    @Test
    void dependingOnLessStableComponentRejected() {
        assertViolation("org.domainforge.fixture.stab", "[稳定依赖]");
    }

    @Test
    void stableDependencyAndNoCycleInLegalLayout() {
        assertTrue(check("fixture.good").stream()
                .noneMatch(item -> item.startsWith("[稳定依赖]") || item.startsWith("[组件环]")));
    }

    @Test
    void apiMustNotHoldImplementation() {
        assertViolation("org.domainforge.fixture.badapi", "[api 纯净]");
    }

    @Test
    void queryMustNotUseDomainRepository() {
        assertViolation("fixture.bad.query", "[查询短路径]");
    }

    @Test
    void domainCannotThrowGenericException() {
        assertViolation("fixture.bad.domainexc", "[领域异常]");
    }

    @Test
    void applicationCannotCatchDomainException() {
        assertViolation("fixture.bad.catchdomain", "[领域异常]");
    }

    @Test
    void aggregateMustReferOtherAggregateById() {
        assertViolation("fixture.bad.aggref", "[DDD 模型]");
    }

    @Test
    void repositoryCannotExposeNonRootEntity() {
        assertViolation("fixture.bad.repoentity", "[仓储]");
    }

    @Test
    void repositoryMustInvolveAggregateRoot() {
        assertViolation("fixture.bad.repoplain", "[仓储]");
    }

    @Test
    void domainCannotUseTransportTypeFromShared() {
        assertViolation("fixture.bad.domainshared", "[技术边界]");
    }

    @Test
    void infrastructureCannotDependOnInterfaces() {
        assertViolation("fixture.bad.infraiface", "[依赖方向]");
    }

    @Test
    void interfacesCannotDependOnDomain() {
        assertViolation("fixture.bad.ifacedomain", "[依赖方向]");
    }

    @Test
    void oneUseCaseMethodMustWriteOneAggregate() {
        List<String> violations = check("fixture.bad.multiwrite");
        assertTrue(violations.stream().anyMatch(item -> item.startsWith("[单聚合事务]") && item.contains(".place ")),
                violations.toString());
        assertTrue(violations.stream().anyMatch(item -> item.startsWith("[单聚合事务]") && item.contains(".viaLambda ")),
                violations.toString());
    }

    @Test
    void metricsReportListsEveryComponent() {
        String report = ComponentRules.report(new ClassFileImporter().importPackages("org.domainforge.fixture.stab"));
        assertTrue(report.contains("org.domainforge.fixture.stab:api\t"), report);
        assertTrue(report.contains("NCCD"), report);
    }
}
