package com.codelab.app.judge;

import java.util.Optional;
import java.util.UUID;

/**
 * 判题任务队列（PRD §7.6 / §14.1 D1）。
 * <p>当前实现是 PG 表 {@code judge_queue}（降级开关形态）；将来换成 RocketMQ 的
 * {@code judge.task} 主题时，只要换这个接口的实现，业务代码零改动。
 */
public interface JudgeTaskQueue {

    record Claimed(long queueId, UUID submissionId) {
    }

    void enqueue(UUID submissionId);

    Optional<Claimed> claim();

    void complete(long queueId);

    void fail(long queueId, String error);

    /** 排队位置（从 1 开始）；已在判题或已完成返回 0。 */
    long queuePosition(UUID submissionId);

    long pendingCount();

    /** 进程重启时把"running"的僵尸任务放回队列。 */
    int requeueStale();
}
