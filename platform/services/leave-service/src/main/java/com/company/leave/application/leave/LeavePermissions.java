package com.company.leave.application.leave;

/**
 * 本服务在 IAM 权限目录中的权限码；安装内容见 resources/iam/permissions.json。三项都需要数据范围。
 */
public final class LeavePermissions {
    /**
     * 起草、提交、撤回本人的申请；范围中的部门是申请人可以挂靠的部门（通常授予“本部门”）。
     */
    public static final String APPLY = "leave:request:apply";
    /**
     * 审批通过、驳回与查看待审申请；只能处理范围内部门的申请（通常授予“本部门”或“本部门及下级”）。
     */
    public static final String APPROVE = "leave:request:approve";
    /**
     * 发放年假天数；只有全部数据范围（ALL）可以发放，部门范围无法判断员工归属，不放行。
     */
    public static final String GRANT = "leave:balance:grant";

    private LeavePermissions() {
    }
}
