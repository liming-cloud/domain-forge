package org.domainforge.iam.interfaces.cli;

import org.domainforge.iam.application.command.account.InitializeAdministrator;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 显式管理初始化命令没有HTTP监听，凭据只来自安全控制台或所有者专用文件。
 */
public final class AdministratorCommand {
    private AdministratorCommand() {
    }

    /**
     * 只接受登录名和密码文件路径，不提供命令行明文密码参数或重置选项。
     */
    public static void run(String[] args, Supplier<ConfigurableApplicationContext> start) {
        String login = null;
        Path passwordFile = null;
        for (String argument : args) {
            if (argument.startsWith("--login-name=") && login == null) login = argument.substring(13);
            else if (argument.startsWith("--password-file=") && passwordFile == null)
                passwordFile = Path.of(argument.substring(16));
            else throw new IllegalArgumentException("初始化只接受--login-name与可选--password-file");
        }
        if (login == null) throw new IllegalArgumentException("缺少明确登录名");
        char[] password = readPassword(passwordFile);
        try (var context = start.get()) {
            long accountId = context.getBean(InitializeAdministrator.class).execute(login, new String(password));
            System.out.println("administrator_initialized accountId=" + accountId);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    /**
     * 文件必须为非符号链接的当前用户专用普通文件；原样读取，不剔除换行或空白。
     */
    private static char[] readPassword(Path file) {
        if (file == null) {
            var console = System.console();
            if (console == null) throw new IllegalArgumentException("无安全控制台，请提供受保护密码文件");
            char[] password = console.readPassword("初始密码：");
            if (password == null) throw new IllegalArgumentException("未提供初始密码");
            return password;
        }
        try {
            Set<PosixFilePermission> permissions =
                    Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(file)
                    || permissions.stream().anyMatch(p -> !p.name().startsWith("OWNER_"))
                    || !Files.getOwner(file, LinkOption.NOFOLLOW_LINKS)
                    .getName()
                    .equals(System.getProperty("user.name"))) {
                throw new IllegalArgumentException("密码文件必须仅当前用户可访问");
            }
            try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                byte[] bytes = input.readNBytes(513);
                if (bytes.length > 512) throw new IllegalArgumentException("密码文件超过512字节");
                String value =
                        StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
                Arrays.fill(bytes, (byte) 0);
                return value.toCharArray();
            }
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("无法安全读取密码文件");
        }
    }
}
