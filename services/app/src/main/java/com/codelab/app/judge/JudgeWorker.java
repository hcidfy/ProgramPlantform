package com.codelab.app.judge;

import com.codelab.app.config.AppProperties;
import com.codelab.app.problem.ProblemRepository;
import com.codelab.app.submission.SubmissionRepository;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 判题工作循环：轮询 {@link JudgeTaskQueue}，并发上限按 {@code app.judge.concurrency}（PRD §14.6 默认 2）。
 * 顺带负责两件后台事：重启后把僵尸任务放回队列、按保留期清理判题目录。
 */
@Component
@Order(3) // 1=迁移 2=首个管理员 3=判题器：必须等表建好才能查 judge_queue
public class JudgeWorker implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(JudgeWorker.class);

    private final AppProperties props;
    private final JudgeTaskQueue queue;
    private final SubmissionRepository submissions;
    private final ProblemRepository problems;
    private final JudgeRunner runner;

    private final AtomicInteger running = new AtomicInteger(0);
    private ScheduledExecutorService poller;
    private ScheduledExecutorService cleanup;

    public JudgeWorker(AppProperties props, JudgeTaskQueue queue, SubmissionRepository submissions,
            ProblemRepository problems, JudgeRunner runner) {
        this.props = props;
        this.queue = queue;
        this.submissions = submissions;
        this.problems = problems;
        this.runner = runner;
    }

    @Override
    public void run(ApplicationArguments args) {
        start();
    }

    public void start() {
        if (!props.judge().enabled()) {
            log.warn("判题已关闭（APP_JUDGE_ENABLED=false）：提交会停在 queued 状态");
            return;
        }
        try {
            int requeued = queue.requeueStale();
            if (requeued > 0) {
                log.warn("发现 {} 个上一次运行遗留的判题任务，已放回队列", requeued);
            }
        } catch (RuntimeException ex) {
            log.error("初始化判题队列失败（judge_queue 表是否已迁移？）: {}", ex.getMessage());
            return;
        }
        int concurrency = Math.max(1, props.judge().concurrency());
        ThreadPoolExecutor pool = (ThreadPoolExecutor) Executors.newFixedThreadPool(concurrency, r -> {
            Thread t = new Thread(r, "judge-worker");
            t.setDaemon(true);
            return t;
        });
        poller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "judge-poller");
            t.setDaemon(true);
            return t;
        });
        poller.scheduleWithFixedDelay(() -> safeTick(pool), 1, 1, TimeUnit.SECONDS);

        cleanup = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "judge-cleanup");
            t.setDaemon(true);
            return t;
        });
        cleanup.scheduleWithFixedDelay(this::safeCleanup, 5, 60, TimeUnit.MINUTES);

        log.info("判题器就绪：并发 {}，目录 {}，保留 {} 小时", concurrency, Path.of(props.judge().dir()).toAbsolutePath(),
                props.judge().retentionHours());
    }

    @PreDestroy
    public void stop() {
        shutdown(poller);
        shutdown(cleanup);
    }

    private void safeTick(ThreadPoolExecutor pool) {
        try {
            tick(pool);
        } catch (RuntimeException ex) {
            log.warn("判题轮询异常: {}", ex.getMessage());
        }
    }

    private void tick(ThreadPoolExecutor pool) {
        int concurrency = Math.max(1, props.judge().concurrency());
        while (running.get() < concurrency) {
            var claimed = queue.claim();
            if (claimed.isEmpty()) {
                return;
            }
            JudgeTaskQueue.Claimed task = claimed.get();
            running.incrementAndGet();
            pool.execute(() -> {
                try {
                    judge(task);
                } catch (RuntimeException ex) {
                    log.error("判题任务异常 queueId={}", task.queueId(), ex);
                    queue.fail(task.queueId(), ex.getMessage());
                } finally {
                    running.decrementAndGet();
                }
            });
        }
    }

    private void judge(JudgeTaskQueue.Claimed task) {
        var submission = submissions.findById(task.submissionId()).orElse(null);
        if (submission == null) {
            log.warn("判题任务对应的提交已不存在，丢弃: {}", task.submissionId());
            queue.complete(task.queueId());
            return;
        }
        submissions.markJudging(submission.id());

        var problem = problems.findById(submission.problemId()).orElse(null);
        if (problem == null) {
            submissions.markJudgeError(submission.id(), "题目不存在");
            queue.fail(task.queueId(), "题目不存在");
            return;
        }
        List<ProblemRepository.TestCaseRow> cases = problems.listTestCases(problem.id());
        if (cases.isEmpty()) {
            submissions.markJudgeError(submission.id(), "题目没有测试用例");
            queue.fail(task.queueId(), "题目没有测试用例");
            return;
        }

        JudgeOutcome outcome = runner.run(submission, problem, cases);
        if ("judge_error".equals(outcome.result())) {
            submissions.markJudgeError(submission.id(), outcome.compileOutput());
            queue.fail(task.queueId(), outcome.compileOutput());
            return;
        }

        submissions.deleteCaseResults(submission.id());
        for (JudgeOutcome.CaseOutcome caseOutcome : outcome.cases()) {
            submissions.insertCaseResult(submission.id(), caseOutcome.seq(), caseOutcome.status(),
                    caseOutcome.timeMs(), caseOutcome.exitCode(), caseOutcome.inputExcerpt(),
                    caseOutcome.expectedExcerpt(), caseOutcome.actualExcerpt(), isHidden(cases, caseOutcome.seq()));
        }
        submissions.finish(submission.id(), outcome.result(), outcome.passedCases(), outcome.totalCases(),
                outcome.score(), outcome.compileOutput());
        queue.complete(task.queueId());
        log.info("判题完成 submission={} result={} {}/{}", submission.id(), outcome.result(),
                outcome.passedCases(), outcome.totalCases());
    }

    private boolean isHidden(List<ProblemRepository.TestCaseRow> cases, int seq) {
        for (ProblemRepository.TestCaseRow row : cases) {
            if (row.seq() == seq) {
                return row.isHidden();
            }
        }
        return false;
    }

    private void safeCleanup() {
        try {
            Path base = Path.of(props.judge().dir());
            if (!Files.isDirectory(base)) {
                return;
            }
            Instant threshold = Instant.now().minus(props.judge().retentionHours(), ChronoUnit.HOURS);
            int removed = 0;
            try (Stream<Path> stream = Files.list(base)) {
                for (Path dir : stream.toList()) {
                    if (!Files.isDirectory(dir)) {
                        continue;
                    }
                    try {
                        Instant modified = Files.getLastModifiedTime(dir).toInstant();
                        if (modified.isBefore(threshold)) {
                            runner.cleanup(dir);
                            removed++;
                        }
                    } catch (IOException ignored) {
                        // 单个目录清理失败不影响其他
                    }
                }
            }
            if (removed > 0) {
                log.info("清理过期判题目录 {} 个", removed);
            }
        } catch (IOException ex) {
            log.warn("清理判题目录失败: {}", ex.getMessage());
        }
    }

    private void shutdown(ScheduledExecutorService executor) {
        if (executor != null) {
            executor.shutdownNow();
        }
    }
}
