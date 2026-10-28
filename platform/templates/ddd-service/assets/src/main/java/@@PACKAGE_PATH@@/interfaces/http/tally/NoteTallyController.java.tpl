package @@PACKAGE@@.interfaces.http.tally;

import cn.dev33.satoken.annotation.SaCheckLogin;
import @@PACKAGE@@.application.query.tally.NoteTallyQueries;
import org.domainforge.access.api.CurrentAccess;
import org.domainforge.shared.ApiResponse;
import org.domainforge.web.api.PlatformJsonApi;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 消费示例入口：当前账号在 @@SOURCE@@ 中创建的便笺数，由事件累加（最终一致）。
 */
@RestController
@PlatformJsonApi
public class NoteTallyController {
    private final NoteTallyQueries tallies;
    private final CurrentAccess access;

    /**
     * 只依赖应用查询与访问组件公开接口。
     */
    public NoteTallyController(NoteTallyQueries tallies, CurrentAccess access) {
        this.tallies = tallies;
        this.access = access;
    }

    /**
     * 只需登录；账号取自可信会话，不接收客户端提交的账号 ID。
     */
    @SaCheckLogin
    @GetMapping("/api/@@NAMESPACE@@/note-tally")
    public ApiResponse<Long> tally() {
        return ApiResponse.success(tallies.count(access.current().accountId()));
    }
}
