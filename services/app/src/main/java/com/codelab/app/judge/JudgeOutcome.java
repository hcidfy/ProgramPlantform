package com.codelab.app.judge;

import java.util.List;

/** 一次判题的总结果（PRD §14.6）。 */
public record JudgeOutcome(String result, int passedCases, int totalCases, int score, String compileOutput,
        List<CaseOutcome> cases) {

    public record CaseOutcome(int seq, String status, int timeMs, Integer exitCode, String inputExcerpt,
            String expectedExcerpt, String actualExcerpt) {
    }
}
