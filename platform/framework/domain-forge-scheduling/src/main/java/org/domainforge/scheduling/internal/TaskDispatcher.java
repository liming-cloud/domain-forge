package org.domainforge.scheduling.internal;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.domainforge.scheduling.api.ScheduledTask;
import org.domainforge.scheduling.api.TaskRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import tech.powerjob.worker.core.processor.ProcessResult;
import tech.powerjob.worker.core.processor.TaskContext;
import tech.powerjob.worker.core.processor.sdk.BasicProcessor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 所有任务共用的 PowerJob 处理器：按任务参数中的任务名分派到对应的 {@link ScheduledTask}。
 *
 * <p>每次执行计数 {@code domainforge.scheduling.run}（标签 {@code task}、{@code result}）并计时；
 * 日志上下文 {@code requestId} 为 {@code task-<实例 ID>}，便于与控制台的执行记录对应。
 */
public final class TaskDispatcher implements BasicProcessor {
    /**
     * PowerJob 中登记的处理器（Spring Bean 名）。
     */
    public static final String BEAN = "domainforgeTaskDispatcher";

    private static final Logger LOG = LoggerFactory.getLogger(TaskDispatcher.class);

    private final Map<String, ScheduledTask> tasks = new HashMap<>();
    private final MeterRegistry metrics;

    /**
     * 任务名重复时启动失败。
     */
    public TaskDispatcher(List<ScheduledTask> tasks, MeterRegistry metrics) {
        for (ScheduledTask task : tasks) {
            if (!task.name().matches("[a-z0-9][a-z0-9-]{0,63}"))
                throw new IllegalStateException("任务名不合法：" + task.name());
            if (this.tasks.put(task.name(), task) != null)
                throw new IllegalStateException("任务名重复：" + task.name());
        }
        this.metrics = metrics;
    }

    @Override
    public ProcessResult process(TaskContext context) {
        String name = context.getJobParams();
        ScheduledTask task = tasks.get(name);
        if (task == null) {
            LOG.error("scheduling_unknown_task task={} instance={}", name, context.getInstanceId());
            return new ProcessResult(false, "本服务没有任务 " + name);
        }
        MDC.put("requestId", "task-" + context.getInstanceId());
        Timer.Sample sample = Timer.start(metrics);
        try {
            task.run(new TaskRun(context.getInstanceId(), parameters(context)));
            record(name, "success", sample);
            return new ProcessResult(true, "ok");
        } catch (Exception failure) {
            record(name, "failure", sample);
            LOG.error("scheduling_task_failed task={} instance={} reason={}", name, context.getInstanceId(),
                    failure.getClass().getName(), failure);
            return new ProcessResult(false, failure.getClass().getName());
        } finally {
            MDC.remove("requestId");
        }
    }

    private static String parameters(TaskContext context) {
        return context.getInstanceParams() == null ? "" : context.getInstanceParams();
    }

    private void record(String task, String result, Timer.Sample sample) {
        Counter.builder("domainforge.scheduling.run").tag("task", task).tag("result", result).register(metrics)
                .increment();
        sample.stop(Timer.builder("domainforge.scheduling.duration").tag("task", task).register(metrics));
    }
}
