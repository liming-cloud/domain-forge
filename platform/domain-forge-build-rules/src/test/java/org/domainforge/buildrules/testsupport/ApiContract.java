package org.domainforge.buildrules.testsupport;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * 接口契约快照（H-05）：服务运行时由 springdoc 生成的 OpenAPI 文档与提交在模块根目录的 {@code openapi.json} 比对，有差异即失败。
 * <p>
 * 比对前去掉随运行地址变化的 {@code servers}，对象的键按字母排序，顶层 {@code tags} 按名称排序，使快照与运行环境、类扫描顺序无关。
 * 接口有意变化时以 {@code -Dapi.contract.update=true} 重跑，写出新快照并随改动提交。快照缺失时本机首次运行写出；
 * 持续集成（设置了环境变量 {@code CI}）中缺失视为失败，防止新服务漏提交快照。
 */
public final class ApiContract {
    /** 快照文件名，位于服务模块根目录。 */
    public static final String SNAPSHOT = "openapi.json";

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();

    private ApiContract() {
    }

    /**
     * 以当前模块（Maven 的 {@code basedir}）根目录的快照校验文档；更新开关与持续集成环境取自系统属性与环境变量。
     */
    public static void verify(String document) {
        Path module = Path.of(System.getProperty("basedir", System.getProperty("user.dir")));
        verify(module.resolve(SNAPSHOT), document, Boolean.getBoolean("api.contract.update"),
                System.getenv("CI") != null);
    }

    /**
     * 以指定快照校验文档：{@code update} 为真时写出快照；快照缺失时 {@code ci} 为假则写出，为真则失败。
     */
    public static void verify(Path snapshot, String document, boolean update, boolean ci) {
        String actual = normalize(document);
        if (update || !Files.exists(snapshot) && !ci) {
            write(snapshot, actual);
            return;
        }
        if (!Files.exists(snapshot)) {
            fail("缺少接口契约快照 " + snapshot + "：本机运行一次 verify 写出快照，检查后随服务提交");
        }
        String expected = read(snapshot);
        if (!expected.equals(actual)) {
            fail("接口契约与快照 " + snapshot + " 不一致，" + firstDifference(expected, actual)
                    + "\n确认对调用方的影响后以 -Dapi.contract.update=true 重跑，更新快照并在 PR 中说明（H-05、H-06）");
        }
    }

    /** 规范化后的文档文本：两空格缩进，末尾换行。 */
    public static String normalize(String document) {
        JsonNode root = JSON.readTree(document);
        if (root instanceof ObjectNode object) {
            object.remove("servers");
            if (object.get("tags") instanceof ArrayNode tags) {
                List<JsonNode> sorted = new ArrayList<>();
                tags.forEach(sorted::add);
                sorted.sort(Comparator.comparing(tag -> tag.path("name").asString()));
                tags.removeAll();
                tags.addAll(sorted);
            }
        }
        return JSON.writeValueAsString(sorted(root)) + "\n";
    }

    private static JsonNode sorted(JsonNode node) {
        if (node instanceof ObjectNode object) {
            ObjectNode result = JSON.createObjectNode();
            object.propertyNames().stream().sorted().forEach(name -> result.set(name, sorted(object.get(name))));
            return result;
        }
        if (node instanceof ArrayNode array) {
            ArrayNode result = JSON.createArrayNode();
            array.forEach(item -> result.add(sorted(item)));
            return result;
        }
        return node;
    }

    private static String firstDifference(String expected, String actual) {
        List<String> left = expected.lines().toList();
        List<String> right = actual.lines().toList();
        int line = 0;
        while (line < left.size() && line < right.size() && left.get(line).equals(right.get(line))) {
            line++;
        }
        return "第 " + (line + 1) + " 行起不同：\n  快照：" + at(left, line) + "\n  实际：" + at(right, line);
    }

    private static String at(List<String> lines, int index) {
        return index < lines.size() ? lines.get(index).strip() : "（文件结束）";
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static void write(Path file, String content) {
        try {
            Files.writeString(file, content);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
