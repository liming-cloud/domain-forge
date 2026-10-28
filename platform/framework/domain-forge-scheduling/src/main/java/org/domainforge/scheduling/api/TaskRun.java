package org.domainforge.scheduling.api;

/**
 * 一次任务执行。
 *
 * @param instanceId PowerJob 的执行实例 ID，与控制台中的执行记录对应
 * @param parameters 手动触发时在控制台填写的参数；周期触发时为空字符串
 */
public record TaskRun(long instanceId, String parameters) {
}
