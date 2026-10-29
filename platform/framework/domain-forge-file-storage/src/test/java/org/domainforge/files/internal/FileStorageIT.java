package org.domainforge.files.internal;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.domainforge.files.api.FileContent;
import org.domainforge.files.api.FileRejected;
import org.domainforge.files.api.FileStorageUnavailableException;
import org.domainforge.files.api.FileStore;
import org.domainforge.files.api.FileUpload;
import org.domainforge.files.api.StoredFile;
import org.domainforge.files.api.UploadPolicy;
import org.domainforge.persistence.autoconfigure.DomainForgePersistenceConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionOperations;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实 PostgreSQL 与 S3 兼容服务（SeaweedFS）上的文件存储合同；本地目录与 S3 两种后端执行同一组场景：
 * 存取与删除、回滚删除对象、策略拒绝、必须在事务内写入、保留期后清理与孤儿清扫、对象缺失、S3 中断后恢复、启动检查。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FileStorageIT {
    private static final UploadPolicy TEXT = UploadPolicy.of(1024 * 1024, "text/plain");
    private static String schema;
    private static Path localRoot;
    private static ConfigurableApplicationContext local;
    private static ConfigurableApplicationContext s3;

    @BeforeAll
    static void start() throws Exception {
        schema = "files_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(new DriverManagerDataSource(url(""), "domainforge", env("POSTGRES_PASSWORD")))
                .execute("CREATE SCHEMA " + schema);
        try (var connection = new DriverManagerDataSource(url(schema), "domainforge", env("POSTGRES_PASSWORD"))
                .getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("domain-forge/files/schema.sql"));
        }
        localRoot = Files.createTempDirectory("files-it-");
        local = start("--domainforge.files.storage=local", "--domainforge.files.local.root=" + localRoot);
        s3 = start(s3Arguments(env("S3_BUCKET")));
        warmUp();
    }

    /**
     * SeaweedFS 的第一次写入要先分配卷（约 10 秒），超过测试的调用时限；先写一次再开始验证。
     */
    private static void warmUp() throws Exception {
        Path source = Files.createTempFile("warm-up-", ".txt");
        Files.writeString(source, "warm-up");
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (true) {
            try {
                storage(s3).write("warm-up", source, Files.size(source));
                storage(s3).delete("warm-up");
                return;
            } catch (FileStorageUnavailableException notYet) {
                if (System.nanoTime() > deadline) throw notYet;
            }
        }
    }

    @AfterAll
    static void stop() {
        if (local != null) local.close();
        if (s3 != null) s3.close();
    }

    private static String env(String name) {
        String value = System.getenv(name);
        assertNotNull(value, "需要真实 PostgreSQL 与 S3 验证环境：缺少 " + name);
        return value;
    }

    private static String url(String schema) {
        return "jdbc:postgresql://127.0.0.1:" + env("POSTGRES_PORT") + "/domainforge"
                + (schema.isEmpty() ? "" : "?currentSchema=" + schema);
    }

    private static String[] s3Arguments(String bucket) {
        return new String[]{"--domainforge.files.storage=s3",
                "--domainforge.files.s3.endpoint=" + env("S3_ENDPOINT"),
                "--domainforge.files.s3.bucket=" + bucket,
                "--domainforge.files.s3.prefix=it-" + UUID.randomUUID() + "/",
                "--domainforge.files.s3.access-key=" + env("S3_ACCESS_KEY"),
                "--domainforge.files.s3.secret-key=" + env("S3_SECRET_KEY"),
                "--domainforge.files.s3.attempt-timeout=PT3S",
                "--domainforge.files.s3.call-timeout=PT8S",
                "--domainforge.files.s3.socket-timeout=PT3S"};
    }

    private static ConfigurableApplicationContext start(String... storage) {
        var arguments = new java.util.ArrayList<>(List.of(
                "--spring.main.banner-mode=off",
                "--logging.level.root=WARN",
                "--spring.application.name=files-it",
                "--spring.datasource.url=" + url(schema),
                "--spring.datasource.username=domainforge",
                "--spring.datasource.password=" + env("POSTGRES_PASSWORD"),
                // 表由 schema.sql 预先创建；持久化组件要求 Flyway，这里只建立基线、不执行迁移。
                "--spring.flyway.schemas=" + schema,
                "--spring.flyway.locations=classpath:files-it-no-migrations",
                "--spring.flyway.baseline-on-migrate=true",
                "--spring.flyway.baseline-version=0",
                "--domainforge.files.enabled=true",
                "--domainforge.files.max-size=64KB",
                "--domainforge.files.grace=PT1S"));
        arguments.addAll(List.of(storage));
        return new SpringApplicationBuilder(Application.class).web(WebApplicationType.NONE)
                .run(arguments.toArray(String[]::new));
    }

    private static List<ConfigurableApplicationContext> backends() {
        return List.of(local, s3);
    }

    private static <T> T inTransaction(ConfigurableApplicationContext context, Supplier<T> work) {
        return context.getBean(TransactionOperations.class).execute(status -> work.get());
    }

    private static StoredFile store(ConfigurableApplicationContext context, String name, String text) {
        return inTransaction(context, () -> context.getBean(FileStore.class).store(upload(name, text), TEXT));
    }

    private static FileUpload upload(String name, String text) {
        return new FileUpload(name, "text/plain; charset=UTF-8",
                new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), 7L);
    }

    private static ObjectStorage storage(ConfigurableApplicationContext context) {
        return context.getBean(FileStorageAssembly.class).storage();
    }

    private static JdbcTemplate jdbc() {
        return new JdbcTemplate(new DriverManagerDataSource(url(schema), "domainforge", env("POSTGRES_PASSWORD")));
    }

    private static String key(String id) {
        return jdbc().queryForObject("SELECT storage_key FROM domainforge_file WHERE id = ?", String.class, id);
    }

    private static int rows(String id) {
        return jdbc().queryForObject("SELECT count(*) FROM domainforge_file WHERE id = ?", Integer.class, id);
    }

    private static String today() {
        return DateTimeFormatter.ofPattern("yyyy/MM/dd/").format(Instant.now().atZone(ZoneOffset.UTC));
    }

    private static boolean exists(ConfigurableApplicationContext context, String key) throws Exception {
        try (InputStream content = storage(context).read(key)) {
            return content != null;
        }
    }

    private static String read(FileContent content) throws Exception {
        try (content) {
            return new String(content.content().readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    // 保存后可读取信息与内容；名称去掉路径、类型去掉参数；删除后读取不到，对象在保留期后由清理任务删除。
    @Test
    void storeOpenAndDelete() throws Exception {
        for (var context : backends()) {
            FileStore files = context.getBean(FileStore.class);
            StoredFile stored = store(context, "../报告\\季度.txt", "第一季度");
            assertEquals("季度.txt", stored.name());
            assertEquals("text/plain", stored.contentType());
            assertEquals(12, stored.size());
            assertEquals(sha256("第一季度"), stored.sha256());
            assertEquals(7L, stored.uploadedBy());
            assertEquals(stored, files.find(stored.id()).orElseThrow());
            assertEquals("第一季度", read(files.open(stored.id()).orElseThrow()));
            String key = key(stored.id());
            assertTrue(inTransaction(context, () -> files.delete(stored.id())));
            assertFalse(inTransaction(context, () -> files.delete(stored.id())));
            assertTrue(files.find(stored.id()).isEmpty());
            assertTrue(files.open(stored.id()).isEmpty());
            assertTrue(exists(context, key), "保留期内对象仍在");
            Thread.sleep(1100);
            context.getBean(FileCleanup.class).purgeDeleted();
            assertFalse(exists(context, key));
            assertEquals(0, rows(stored.id()));
        }
    }

    // 业务事务回滚：文件信息不提交，刚写入的对象随即删除。
    @Test
    void rollbackRemovesObject() throws Exception {
        for (var context : backends()) {
            String[] id = new String[1];
            assertThrows(IllegalStateException.class, () -> inTransaction(context, () -> {
                id[0] = context.getBean(FileStore.class).store(upload("a.txt", "回滚"), TEXT).id();
                throw new IllegalStateException("业务失败");
            }));
            assertEquals(0, rows(id[0]));
            assertTrue(storage(context).list(today()).stream().noneMatch(object -> object.key().endsWith(id[0])));
        }
    }

    // 类型不在白名单、超过用例上限或服务全局上限、空文件、文件名无效均被拒绝，什么也不写入。
    @Test
    void policyRejections() {
        for (var context : backends()) {
            FileStore files = context.getBean(FileStore.class);
            int before = storage(context).list(today()).size();
            assertEquals(FileRejected.Reason.TYPE_NOT_ALLOWED, rejected(context, () -> files.store(
                    new FileUpload("a.html", "text/html", new ByteArrayInputStream(new byte[1]), null), TEXT)));
            assertEquals(FileRejected.Reason.TOO_LARGE, rejected(context, () -> files.store(
                    upload("a.txt", "x".repeat(11)), UploadPolicy.of(10, "text/plain"))));
            assertEquals(FileRejected.Reason.TOO_LARGE, rejected(context, () -> files.store(
                    upload("a.txt", "x".repeat(65 * 1024)), TEXT)));
            assertEquals(FileRejected.Reason.EMPTY, rejected(context, () -> files.store(upload("a.txt", ""), TEXT)));
            assertEquals(FileRejected.Reason.NAME_INVALID, rejected(context, () -> files.store(
                    upload("dir/..", "x"), TEXT)));
            assertEquals(before, storage(context).list(today()).size());
        }
    }

    private static FileRejected.Reason rejected(ConfigurableApplicationContext context, Supplier<StoredFile> work) {
        return assertThrows(FileRejected.class, () -> inTransaction(context, work)).reason();
    }

    // 写入与删除必须在业务事务内，避免文件信息与业务数据分开提交。
    @Test
    void writesRequireTransaction() {
        FileStore files = local.getBean(FileStore.class);
        assertThrows(IllegalStateException.class, () -> files.store(upload("a.txt", "x"), TEXT));
        assertThrows(IllegalStateException.class, () -> files.delete(UUID.randomUUID().toString()));
        assertTrue(files.find("not-a-uuid").isEmpty());
    }

    // 没有记录的对象（提交前中断、恢复备份后多出的文件）超过保留期后被清扫，有记录的对象保留。
    @Test
    void orphansAreSweptAfterGrace() throws Exception {
        for (var context : backends()) {
            StoredFile kept = store(context, "kept.txt", "保留");
            String orphan = today() + UUID.randomUUID();
            Path source = Files.createTempFile("orphan-", ".txt");
            Files.writeString(source, "孤儿");
            storage(context).write(orphan, source, Files.size(source));
            Thread.sleep(1100);
            assertTrue(context.getBean(FileCleanup.class).sweepOrphans(1) >= 1);
            assertFalse(exists(context, orphan));
            assertTrue(exists(context, key(kept.id())));
            assertThrows(IllegalArgumentException.class, () -> FileCleanup.days("weeks=2"));
            assertEquals(30, FileCleanup.days("days=30"));
        }
    }

    // 文件信息存在而对象缺失是数据不一致，不能当作“不存在”静默处理。
    @Test
    void missingObjectIsAnError() {
        for (var context : backends()) {
            StoredFile stored = store(context, "lost.txt", "丢失");
            storage(context).delete(key(stored.id()));
            assertThrows(IllegalStateException.class, () -> context.getBean(FileStore.class).open(stored.id()));
        }
    }

    // S3 不可用（容器暂停，请求超时）：写入失败为 FileStorageUnavailableException 且业务事务回滚；
    // 恢复后无需重启即可继续写入。暂停而不是停止容器：重启会改变随机映射的端口。
    @Test
    @Order(Integer.MAX_VALUE)
    void s3OutageFailsAndRecovers() throws Exception {
        long before = jdbc().queryForObject("SELECT count(*) FROM domainforge_file", Long.class);
        docker("pause");
        try {
            assertThrows(FileStorageUnavailableException.class, () -> store(s3, "down.txt", "停机"));
        } finally {
            docker("unpause");
        }
        assertEquals(before, jdbc().queryForObject("SELECT count(*) FROM domainforge_file", Long.class));
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        StoredFile stored = null;
        while (stored == null && System.nanoTime() < deadline) {
            try {
                stored = store(s3, "up.txt", "恢复");
            } catch (FileStorageUnavailableException notYet) {
                Thread.sleep(500);
            }
        }
        assertNotNull(stored, "S3 恢复后 90 秒内仍无法写入");
        assertEquals("恢复", read(s3.getBean(FileStore.class).open(stored.id()).orElseThrow()));
    }

    // 启动检查：桶不存在或本地目录不可写时启动失败，而不是在第一次上传时才暴露。
    @Test
    void unusableStorageFailsStartup() throws Exception {
        assertThrows(Exception.class, () -> start(s3Arguments("missing-" + UUID.randomUUID())).close());
        Path file = Files.createTempFile("not-a-directory-", ".txt");
        assertThrows(Exception.class, () -> start("--domainforge.files.storage=local",
                "--domainforge.files.local.root=" + file).close());
    }

    private static void docker(String command) throws Exception {
        var process = new ProcessBuilder("docker", command, env("SEAWEEDFS_CONTAINER"))
                .redirectErrorStream(true).start();
        assertEquals(0, process.waitFor(), "docker " + command + " 失败");
    }

    /**
     * 自动配置＋持久化组件。
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import(DomainForgePersistenceConfiguration.class)
    static class Application {
        @Bean
        SimpleMeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
