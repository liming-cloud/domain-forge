package com.company.leave.interfaces.http.leave;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.company.leave.application.command.leave.ApplyLeave;
import com.company.leave.application.command.leave.ApproveLeave;
import com.company.leave.application.leave.LeavePermissions;
import com.company.leave.application.query.leave.LeaveQueries;
import com.company.leave.application.query.leave.ListPendingLeave;
import org.domainforge.access.api.CurrentAccess;
import org.domainforge.web.api.PlatformJsonApi;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 请假申请入口：申请人起草、提交、撤回，审批人通过、驳回；查询本人的申请与待我审批的申请。
 *
 * <p>权限码由原生注解在入口检查；申请归属、审批部门范围与申请版本由应用用例按对象核对。状态命令须携带读取时的申请版本。
 */
@RestController
@PlatformJsonApi
public class LeaveRequestController {
    private final ApplyLeave apply;
    private final ApproveLeave approve;
    private final LeaveQueries queries;
    private final ListPendingLeave pending;
    private final CurrentAccess access;

    /**
     * 只依赖应用用例、查询合同与访问组件公开接口。
     */
    public LeaveRequestController(ApplyLeave apply, ApproveLeave approve, LeaveQueries queries,
            ListPendingLeave pending, CurrentAccess access) {
        this.apply = apply;
        this.approve = approve;
        this.queries = queries;
        this.pending = pending;
        this.access = access;
    }

    /**
     * 命令：起草本人的申请，挂靠在申请权限范围内的部门。
     */
    @SaCheckPermission(LeavePermissions.APPLY)
    @PostMapping("/api/leave-requests")
    public LeaveChangeResponse draft(@RequestBody DraftLeaveRequest input) {
        return LeaveChangeResponse.of(apply.draft(input.unitId(), input.startDate(), input.endDate()));
    }

    /**
     * 命令：本人的草稿提交审批。
     */
    @SaCheckPermission(LeavePermissions.APPLY)
    @PostMapping("/api/leave-requests/{id}/submit")
    public LeaveChangeResponse submit(@PathVariable long id, @RequestBody LeaveVersionRequest input) {
        return LeaveChangeResponse.of(apply.submit(id, input.expectedVersion()));
    }

    /**
     * 命令：本人在审批前撤回申请。
     */
    @SaCheckPermission(LeavePermissions.APPLY)
    @PostMapping("/api/leave-requests/{id}/withdraw")
    public LeaveChangeResponse withdraw(@PathVariable long id, @RequestBody LeaveVersionRequest input) {
        return LeaveChangeResponse.of(apply.withdraw(id, input.expectedVersion()));
    }

    /**
     * 命令：审批通过范围内部门的待审申请，扣减申请人的余额；不能审批本人的申请。
     */
    @SaCheckPermission(LeavePermissions.APPROVE)
    @PostMapping("/api/leave-requests/{id}/approve")
    public LeaveChangeResponse approve(@PathVariable long id, @RequestBody LeaveVersionRequest input) {
        return LeaveChangeResponse.of(approve.approve(id, input.expectedVersion()));
    }

    /**
     * 命令：驳回范围内部门的待审申请；不能驳回本人的申请。
     */
    @SaCheckPermission(LeavePermissions.APPROVE)
    @PostMapping("/api/leave-requests/{id}/reject")
    public LeaveChangeResponse reject(@PathVariable long id, @RequestBody LeaveVersionRequest input) {
        return LeaveChangeResponse.of(approve.reject(id, input.expectedVersion()));
    }

    /**
     * 纯查询：本人的全部申请，只需登录。
     */
    @SaCheckLogin
    @GetMapping("/api/me/leave-requests")
    public List<LeaveRequestResponse> mine(
            @RequestParam(defaultValue = "0") long after, @RequestParam(defaultValue = "20") int limit) {
        return queries.mine(access.current().accountId(), after, limit).stream()
                .map(LeaveRequestResponse::of).toList();
    }

    /**
     * 需要编排的查询：待我审批的申请，按审批权限的部门范围过滤。
     */
    @SaCheckPermission(LeavePermissions.APPROVE)
    @GetMapping("/api/leave-requests/pending")
    public List<LeaveRequestResponse> pending(
            @RequestParam(defaultValue = "0") long after, @RequestParam(defaultValue = "20") int limit) {
        return pending.list(after, limit).stream().map(LeaveRequestResponse::of).toList();
    }
}
