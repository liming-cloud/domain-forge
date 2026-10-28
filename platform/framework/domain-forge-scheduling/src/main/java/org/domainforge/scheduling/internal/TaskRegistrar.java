package org.domainforge.scheduling.internal;

import org.domainforge.scheduling.api.ScheduledTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.powerjob.client.PowerJobClient;
import tech.powerjob.common.enums.ExecuteType;
import tech.powerjob.common.enums.ProcessorType;
import tech.powerjob.common.enums.TimeExpressionType;
import tech.powerjob.common.request.http.SaveJobInfoRequest;
import tech.powerjob.common.response.JobInfoDTO;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 启动时把本服务声明的任务登记到 PowerJob：按任务名查找，不存在才创建；已存在的任务一律不改动，
 * 控制台中修改的周期、暂停状态保持有效（与 Nacos 种子“不存在才发布”的规则一致）。
 *
 * <p>创建时固定执行语义：单机执行、同一时刻最多一个实例、实例与子任务都不重试、Cron 周期、启用。
 * 登记失败（服务端不可达、应用未注册或密码不符）使启动失败，不在没有调度的情况下静默运行。
 */
public final class TaskRegistrar {
    private static final Logger LOG = LoggerFactory.getLogger(TaskRegistrar.class);

    private final List<ScheduledTask> tasks;
    private final PowerJobClient client;

    /**
     * 客户端以应用名与应用密码连接 PowerJob 服务端。
     */
    public TaskRegistrar(List<ScheduledTask> tasks, PowerJobClient client) {
        this.tasks = List.copyOf(tasks);
        this.client = client;
    }

    /**
     * 新建任务的请求：执行语义固定，周期与说明来自任务声明。
     */
    static SaveJobInfoRequest request(ScheduledTask task) {
        var request = new SaveJobInfoRequest();
        request.setJobName(task.name());
        request.setJobDescription(task.description());
        request.setJobParams(task.name());
        request.setTimeExpressionType(TimeExpressionType.CRON);
        request.setTimeExpression(task.cron());
        request.setExecuteType(ExecuteType.STANDALONE);
        request.setProcessorType(ProcessorType.BUILT_IN);
        request.setProcessorInfo(TaskDispatcher.BEAN);
        request.setMaxInstanceNum(1);
        request.setConcurrency(1);
        request.setInstanceRetryNum(0);
        request.setTaskRetryNum(0);
        request.setEnable(true);
        return request;
    }

    /**
     * 登记全部任务，返回新建的任务名。
     */
    public Set<String> register() {
        var fetched = client.fetchAllJob();
        if (!fetched.isSuccess()) throw new IllegalStateException("无法读取 PowerJob 任务：" + fetched.getMessage());
        Set<String> existing = fetched.getData().stream().map(JobInfoDTO::getJobName).collect(Collectors.toSet());
        Set<String> created = new java.util.TreeSet<>();
        for (ScheduledTask task : tasks) {
            if (existing.contains(task.name())) {
                LOG.info("scheduling_task_kept task={}", task.name());
                continue;
            }
            var saved = client.saveJob(request(task));
            if (!saved.isSuccess())
                throw new IllegalStateException("无法登记任务 " + task.name() + "：" + saved.getMessage());
            created.add(task.name());
            LOG.info("scheduling_task_created task={} id={} cron={}", task.name(), saved.getData(), task.cron());
        }
        return created;
    }
}
