package org.domainforge.scheduling.api;

/**
 * 一个定时任务。服务把实现声明为 Spring Bean，组件在启动时把它登记到 PowerJob（已存在则不改动），由 PowerJob 按周期调度。
 *
 * <p>执行语义固定：同一任务同一时刻只在一个实例上执行，上次未结束时不开始新的执行（这些触发被跳过；
 * 若实例已创建却无法开始，执行记录中为失败“too many instances”）；停机期间错过的触发不补跑；
 * 失败不自动重试，记录并计数，下个周期再执行。
 * 因此任务必须可以安全地重复执行，也不能依赖某次触发一定发生。
 */
public interface ScheduledTask {
    /**
     * 任务名：应用内唯一，1—64 位小写字母、数字与 {@code -}，作为 PowerJob 中的任务名称。
     */
    String name();

    /**
     * 初始周期：带秒的 Cron 表达式（如 {@code 0 0 3 * * ?}）。只在首次登记时使用，之后以 PowerJob 控制台中的周期为准。
     */
    String cron();

    /**
     * 任务说明，显示在控制台。
     */
    String description();

    /**
     * 执行一次。抛出异常即本次失败；没有用户身份与请求上下文，需要调用其他服务时只能调用服务专用接口。
     *
     * @param run 本次执行的信息
     * @throws Exception 执行失败
     */
    void run(TaskRun run) throws Exception;
}
