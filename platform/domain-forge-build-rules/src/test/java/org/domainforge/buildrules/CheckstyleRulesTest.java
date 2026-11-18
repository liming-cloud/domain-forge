package org.domainforge.buildrules;

import com.puppycrawl.tools.checkstyle.Checker;
import com.puppycrawl.tools.checkstyle.ConfigurationLoader;
import com.puppycrawl.tools.checkstyle.PropertiesExpander;
import com.puppycrawl.tools.checkstyle.api.AuditEvent;
import com.puppycrawl.tools.checkstyle.api.AuditListener;
import com.puppycrawl.tools.checkstyle.api.CheckstyleException;
import com.puppycrawl.tools.checkstyle.api.Configuration;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * Checkstyle 规则的正反例：正例在三份配置下零违规；反例以检查名命名（可加 _变体），在配置了该检查的每份配置下只报出该检查；
 * 每个已配置的检查至少有一个反例。
 */
class CheckstyleRulesTest {
    private static final Path RULES = Path.of("src/main/resources/checkstyle");
    private static final Path FIXTURES = Path.of("src/test/resources/checkstyle");
    private static final List<String> CONFIGS = List.of("main.xml", "test.xml", "format.xml");
    private static final Set<String> CONTAINERS = Set.of("Checker", "TreeWalker");

    @TestFactory
    List<DynamicTest> legalSourcePasses() throws IOException {
        List<DynamicTest> tests = new ArrayList<>();
        for (String config : CONFIGS) {
            for (Path file : files("good")) {
                tests.add(dynamicTest(config + " " + file.getFileName(),
                        () -> assertEquals(List.of(), audit(config, file))));
            }
        }
        return tests;
    }

    @TestFactory
    List<DynamicTest> violationReportedByItsCheck() throws IOException, CheckstyleException {
        List<DynamicTest> tests = new ArrayList<>();
        for (String config : CONFIGS) {
            Set<String> checks = checks(config);
            for (Path file : files("bad")) {
                String check = checkOf(file);
                if (checks.contains(check)) {
                    tests.add(dynamicTest(config + " " + file.getFileName(), () -> {
                        List<String> found = audit(config, file);
                        assertTrue(!found.isEmpty() && Set.copyOf(found).equals(Set.of(check)),
                                () -> file + " 应只报出 " + check + "，实际：" + found);
                    }));
                }
            }
        }
        return tests;
    }

    @Test
    void everyCheckHasViolatingExample() throws IOException, CheckstyleException {
        Set<String> examples = new HashSet<>();
        for (Path file : files("bad")) {
            examples.add(checkOf(file));
        }
        Set<String> configured = new HashSet<>();
        for (String config : CONFIGS) {
            configured.addAll(checks(config));
        }
        assertEquals(configured, examples, "反例与已配置的检查应一一对应");
    }

    private static List<Path> files(String kind) throws IOException {
        try (Stream<Path> files = Files.list(FIXTURES.resolve(kind))) {
            return files.sorted().toList();
        }
    }

    private static String checkOf(Path file) {
        return file.getFileName().toString().replaceFirst("(_.*)?\\.java$", "");
    }

    private static Configuration load(String config) throws CheckstyleException {
        return ConfigurationLoader.loadConfiguration(
                RULES.resolve(config).toString(), new PropertiesExpander(new Properties()));
    }

    private static Set<String> checks(String config) throws CheckstyleException {
        Set<String> names = new HashSet<>();
        collect(load(config), names);
        return names;
    }

    private static void collect(Configuration module, Set<String> names) {
        if (!CONTAINERS.contains(module.getName())) {
            names.add(module.getName());
        }
        for (Configuration child : module.getChildren()) {
            collect(child, names);
        }
    }

    /**
     * 用指定配置检查一个文件，返回报出违规的检查名（去掉包名与 Check 后缀）。
     */
    private static List<String> audit(String config, Path file) throws CheckstyleException {
        List<String> found = new ArrayList<>();
        Checker checker = new Checker();
        checker.setModuleClassLoader(Checker.class.getClassLoader());
        checker.configure(load(config));
        checker.addListener(new AuditListener() {
            @Override
            public void auditStarted(AuditEvent event) {
            }

            @Override
            public void auditFinished(AuditEvent event) {
            }

            @Override
            public void fileStarted(AuditEvent event) {
            }

            @Override
            public void fileFinished(AuditEvent event) {
            }

            @Override
            public void addError(AuditEvent event) {
                String source = event.getSourceName();
                found.add(source.substring(source.lastIndexOf('.') + 1).replaceFirst("Check$", ""));
            }

            @Override
            public void addException(AuditEvent event, Throwable failure) {
                throw new IllegalStateException(event.getFileName(), failure);
            }
        });
        try {
            checker.process(List.of(file.toFile()));
        } finally {
            checker.destroy();
        }
        return found;
    }
}
