package com.codelab.app.judge;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** PG 队列实现：{@code FOR UPDATE SKIP LOCKED} 抢任务，天然支持多线程/多进程。 */
@Repository
public class PgJudgeTaskQueue implements JudgeTaskQueue {

    private final JdbcClient jdbc;

    public PgJudgeTaskQueue(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void enqueue(UUID submissionId) {
        jdbc.sql("""
                        INSERT INTO judge_queue (submission_id) VALUES (:sid)
                        ON CONFLICT (submission_id) DO UPDATE
                           SET status = 'queued', enqueued_at = now(), finished_at = NULL, last_error = NULL
                        """)
                .param("sid", submissionId)
                .update();
    }

    @Override
    public Optional<Claimed> claim() {
        return jdbc.sql("""
                        UPDATE judge_queue
                           SET status = 'running', started_at = now(), attempts = attempts + 1
                         WHERE id = (SELECT id FROM judge_queue
                                      WHERE status = 'queued'
                                      ORDER BY enqueued_at, id
                                      FOR UPDATE SKIP LOCKED
                                      LIMIT 1)
                        RETURNING id, submission_id
                        """)
                .query((rs, i) -> new Claimed(rs.getLong("id"), rs.getObject("submission_id", UUID.class)))
                .optional();
    }

    @Override
    public void complete(long queueId) {
        jdbc.sql("UPDATE judge_queue SET status = 'done', finished_at = now() WHERE id = :id")
                .param("id", queueId)
                .update();
    }

    @Override
    public void fail(long queueId, String error) {
        String trimmed = error == null ? null : error.length() > 2000 ? error.substring(0, 2000) : error;
        jdbc.sql("UPDATE judge_queue SET status = 'failed', finished_at = now(), last_error = :err WHERE id = :id")
                .param("id", queueId)
                .param("err", trimmed)
                .update();
    }

    @Override
    public long queuePosition(UUID submissionId) {
        return jdbc.sql("""
                        SELECT count(*)
                          FROM judge_queue q
                         WHERE q.status = 'queued'
                           AND q.enqueued_at <= (SELECT enqueued_at FROM judge_queue WHERE submission_id = :sid)
                        """)
                .param("sid", submissionId)
                .query(Long.class)
                .single();
    }

    @Override
    public long pendingCount() {
        return jdbc.sql("SELECT count(*) FROM judge_queue WHERE status IN ('queued','running')")
                .query(Long.class)
                .single();
    }

    @Override
    public int requeueStale() {
        return jdbc.sql("""
                        UPDATE judge_queue SET status = 'queued', started_at = NULL
                         WHERE status = 'running'
                        """).update();
    }
}
