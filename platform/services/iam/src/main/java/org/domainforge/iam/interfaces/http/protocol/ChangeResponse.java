package org.domainforge.iam.interfaces.http.protocol;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.domainforge.iam.application.command.CommandResult;

/**
 * 命令完成后的对象 ID 与新版本；ID 以十进制字符串传输，避免 JavaScript 精度丢失。
 *
 * @param id      被创建或修改的对象 ID
 * @param version 下一次修改须携带的版本
 */
public record ChangeResponse(@JsonFormat(shape = JsonFormat.Shape.STRING) long id, long version) {
    /**
     * 从应用层结果转换。
     */
    public static ChangeResponse of(CommandResult result) {
        return new ChangeResponse(result.targetId(), result.version());
    }
}
