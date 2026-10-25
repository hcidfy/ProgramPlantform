package com.codelab.app.ai;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 提示词模板（PRD §8.4：改提示词不动代码）。 */
@Repository
public class PromptTemplateRepository {

    public record PromptTemplate(String task, int version, String body) {
    }

    private final JdbcClient jdbc;

    public PromptTemplateRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<PromptTemplate> findActive(String task) {
        return jdbc.sql("""
                        SELECT task, version, body
                          FROM prompt_templates
                         WHERE task = :task AND status = 'active'
                         ORDER BY version DESC
                         LIMIT 1
                        """)
                .param("task", task)
                .query((rs, i) -> new PromptTemplate(rs.getString("task"), rs.getInt("version"), rs.getString("body")))
                .optional();
    }

    public void archiveAll(String task) {
        jdbc.sql("UPDATE prompt_templates SET status = 'archived' WHERE task = :task").param("task", task).update();
    }
}
