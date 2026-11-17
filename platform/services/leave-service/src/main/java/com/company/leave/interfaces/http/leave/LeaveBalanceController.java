package com.company.leave.interfaces.http.leave;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.company.leave.application.command.leave.BalanceChange;
import com.company.leave.application.command.leave.GrantLeave;
import com.company.leave.application.leave.LeavePermissions;
import com.company.leave.application.query.leave.LeaveBalanceView;
import com.company.leave.application.query.leave.LeaveQueries;
import org.domainforge.access.api.CurrentAccess;
import org.domainforge.web.api.PlatformJsonApi;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 年假余额入口：本人查询余额，有发放权限（ALL 范围）的人给员工发放天数。
 */
@RestController
@PlatformJsonApi
public class LeaveBalanceController {
    private final GrantLeave grant;
    private final LeaveQueries queries;
    private final CurrentAccess access;

    /**
     * 只依赖应用用例、查询合同与访问组件公开接口。
     */
    public LeaveBalanceController(GrantLeave grant, LeaveQueries queries, CurrentAccess access) {
        this.grant = grant;
        this.queries = queries;
        this.access = access;
    }

    /**
     * 纯查询：本人的剩余年假天数，只需登录。
     */
    @SaCheckLogin
    @GetMapping("/api/me/leave-balance")
    public LeaveBalanceResponse mine() {
        LeaveBalanceView view = queries.balance(access.current().accountId());
        return new LeaveBalanceResponse(view.accountId(), view.remainingDays());
    }

    /**
     * 命令：给员工发放年假天数，返回发放后的余额。
     */
    @SaCheckPermission(LeavePermissions.GRANT)
    @PostMapping("/api/leave-balances/{accountId}/grants")
    public LeaveBalanceResponse grant(@PathVariable long accountId, @RequestBody GrantLeaveRequest input) {
        BalanceChange change = grant.grant(accountId, input.days());
        return new LeaveBalanceResponse(change.accountId(), change.remainingDays());
    }
}
