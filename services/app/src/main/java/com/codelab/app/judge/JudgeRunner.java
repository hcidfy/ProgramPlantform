package com.codelab.app.judge;

import com.codelab.app.config.AppProperties;
import com.codelab.app.problem.ProblemRepository;
import com.codelab.app.submission.SubmissionRepository;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 判题执行器：受限子进程（PRD §7.4 / §14.6）。
 * <p>不是沙箱：靠低权限用户 + ulimit + 超时 + 输出截断 + 语言白名单降低风险。
 * <p>Linux 走 {@code bash -c 'ulimit ...; exec ...'}；开发机的 Windows 分支没有 ulimit，只保留超时，
 * 仅用于本机自验。
 */
@Component
public class JudgeRunner {

    private static final Logger log = LoggerFactory.getLogger(JudgeRunner.class);
    private static final int MAX_OUTPUT_BYTES = 64 * 1024;
    private static final int MAX_COMPILE_BYTES = 4 * 1024;
    private static final int EXCERPT_CHARS = 500;
    private static final long COMPILE_TIMEOUT_MS = 10_000;
    private static final long TOTAL_BUDGET_MS = 60_000;

    private final AppProperties props;
    private final boolean windows;
    private final String javaHome;

    public JudgeRunner(AppProperties props) {
        this.props = props;
        this.windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        this.javaHome = System.getProperty("java.home", "");
    }

    public JudgeOutcome run(SubmissionRepository.SubmissionRow submission, ProblemRepository.ProblemRow problem,
            List<ProblemRepository.TestCaseRow> cases) {
        int total = cases.size();
        boolean java = "java".equals(problem.language());
        Path workDir = Path.of(props.judge().dir(), submission.id().toString());
        List<JudgeOutcome.CaseOutcome> outcomes = new ArrayList<>();
        try {
            prepareWorkDir(workDir);
            writeSource(workDir, java ? "Main.java" : "main.py", submission.sourceCode());

            String compileOutput = "";
            if (java) {
                Path compileLog = workDir.resolve("compile.log");
                ExecResult compiled = exec(List.of(javaTool("javac"), "Main.java"), workDir, null, compileLog,
                        workDir.resolve("compile.err"), COMPILE_TIMEOUT_MS);
                compileOutput = readCapped(compileLog, MAX_COMPILE_BYTES);
                if (compiled.timedOut() || compiled.exitCode() != 0) {
                    if (compiled.timedOut()) {
                        compileOutput = "编译超时（>10s）\n" + compileOutput;
                    }
                    String err = readCapped(workDir.resolve("compile.err"), MAX_COMPILE_BYTES);
                    if (!err.isBlank()) {
                        compileOutput = compileOutput + "\n" + err;
                    }
                    return new JudgeOutcome("compile_error", 0, total, 0, trim(compileOutput, MAX_COMPILE_BYTES),
                            List.of());
                }
            }

            long deadline = System.currentTimeMillis() + TOTAL_BUDGET_MS;
            int passed = 0;
            int score = 0;
            boolean anyTimeout = false;
            boolean anyRuntimeError = false;
            int seq = 0;
            Path in = workDir.resolve("in.txt");
            Path out = workDir.resolve("out.txt");
            Path err = workDir.resolve("err.txt");

            for (ProblemRepository.TestCaseRow testCase : cases) {
                seq++;
                if (System.currentTimeMillis() > deadline) {
                    outcomes.add(new JudgeOutcome.CaseOutcome(seq, "tle", 0, null,
                            trim(testCase.input(), EXCERPT_CHARS), "", "总时长超限，未执行"));
                    anyTimeout = true;
                    continue;
                }
                Files.writeString(in, testCase.input() == null ? "" : testCase.input(), StandardCharsets.UTF_8);
                Files.deleteIfExists(out);
                Files.deleteIfExists(err);

                long timeoutMs = Math.min(problem.timeLimitMs() * 2L, 20_000L);
                ExecResult exec = exec(buildRunCommand(problem, java), workDir, in, out, err, timeoutMs);
                String actual = readCapped(out, MAX_OUTPUT_BYTES);

                String status;
                if (exec.timedOut() || isTimeoutExit(exec.exitCode())) {
                    status = "tle";
                    anyTimeout = true;
                    actual = "";
                } else if (exec.exitCode() != 0) {
                    status = "re";
                    anyRuntimeError = true;
                } else if (normalize(actual).equals(normalize(testCase.expected()))) {
                    status = "ac";
                } else {
                    status = "wa";
                }
                if ("ac".equals(status)) {
                    passed++;
                    score += testCase.score();
                }
                outcomes.add(new JudgeOutcome.CaseOutcome(seq, status, exec.elapsedMs(),
                        exec.timedOut() ? null : exec.exitCode(),
                        trim(testCase.input(), EXCERPT_CHARS),
                        trim(testCase.expected(), EXCERPT_CHARS), trim(actual, EXCERPT_CHARS)));
            }

            String result;
            if (passed == total && total > 0) {
                result = "accepted";
            } else if (anyTimeout) {
                result = "timeout";
            } else if (anyRuntimeError) {
                result = "runtime_error";
            } else {
                result = "wrong_answer";
            }
            return new JudgeOutcome(result, passed, total, score, null, outcomes);
        } catch (Exception ex) {
            log.error("判题执行异常 submission={}", submission.id(), ex);
            return new JudgeOutcome("judge_error", 0, total, 0, "判题执行异常: " + ex.getMessage(), List.of());
        }
    }

    /** 结束后保留工作目录（PRD §7.4：留 24 h 便于教师排查，由 JudgeWorker 定时清理）。 */
    public void cleanup(Path dir) {
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 清理失败不影响主流程
                }
            });
        } catch (IOException ex) {
            log.warn("清理判题目录失败 {}: {}", dir, ex.getMessage());
        }
    }

    // ---------------- 内部 ----------------

    private void prepareWorkDir(Path workDir) throws IOException {
        Files.createDirectories(workDir);
        if (!windows) {
            try {
                Files.setPosixFilePermissions(workDir, Set.of(
                        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
                        PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
                        PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE,
                        PosixFilePermission.OTHERS_EXECUTE));
            } catch (UnsupportedOperationException ignored) {
                // 非 posix 文件系统
            }
        }
    }

    private void writeSource(Path workDir, String fileName, String source) throws IOException {
        Path target = workDir.resolve(fileName);
        Files.writeString(target, source, StandardCharsets.UTF_8);
        if (!windows) {
            try {
                Files.setPosixFilePermissions(target, Set.of(
                        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ));
            } catch (UnsupportedOperationException ignored) {
                // ignore
            }
        }
    }

    private List<String> buildRunCommand(ProblemRepository.ProblemRow problem, boolean java) {
        int memMb = Math.max(64, problem.memLimitKb() / 1024);
        String python = pythonCmd();
        if (windows) {
            return java
                    ? List.of(javaTool("java"), "-Xmx" + memMb + "M", "-cp", ".", "Main")
                    : List.of(python, "main.py");
        }
        // 外层 waitFor 是主超时（时序×2）；ulimit -t 留 2 秒余量做兜底，避免内核 SIGXCPU 抢先导致误判成 RE
        int seconds = (int) Math.max(1, (Math.min(problem.timeLimitMs() * 2L, 20_000L) + 999) / 1000) + 2;
        String inner = java
                ? "exec " + javaTool("java") + " -Xmx" + memMb + "M -cp . Main"
                // Python 才加 -v（虚拟内存上限）；Java 加 -v 会因 JVM 预留虚拟内存直接起不来
                : "exec " + python + " main.py";
        String ulimit = "ulimit -t " + seconds + " -u 64 -f 2048"
                + (java ? "" : " -v " + problem.memLimitKb());
        String script = ulimit + "; " + inner;
        String runAsUser = props.judge().runAsUser();
        List<String> command = new ArrayList<>();
        if (runAsUser != null && !runAsUser.isBlank()) {
            command.addAll(List.of("runuser", "-u", runAsUser.trim(), "--"));
        }
        command.addAll(List.of("bash", "-c", script));
        return command;
    }

    private String javaTool(String name) {
        if (!windows) {
            return name;
        }
        if (!javaHome.isBlank()) {
            Path candidate = Path.of(javaHome, "bin", name + ".exe");
            if (Files.isExecutable(candidate)) {
                return candidate.toString();
            }
        }
        return name;
    }

    private String pythonCmd() {
        String configured = props.judge().pythonCmd();
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        return windows ? "python" : "python3";
    }

    /** 152=SIGXCPU(128+24)、137=SIGKILL(128+9)、124=timeout(1)：都按超时算，别误报成运行错误。 */
    private static boolean isTimeoutExit(int exitCode) {
        return exitCode == 152 || exitCode == 137 || exitCode == 124;
    }

    private record ExecResult(int exitCode, boolean timedOut, int elapsedMs) {
    }

    private ExecResult exec(List<String> command, Path workDir, Path stdin, Path stdout, Path stderr, long timeoutMs)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command).directory(workDir.toFile());
        builder.redirectInput(stdin == null ? ProcessBuilder.Redirect.PIPE
                : ProcessBuilder.Redirect.from(stdin.toFile()));
        builder.redirectOutput(stdout.toFile());
        builder.redirectError(stderr.toFile());
        long start = System.nanoTime();
        Process process = builder.start();
        if (stdin == null) {
            // 用 PIPE + 立刻关闭，等价于"空 stdin"；DISCARD 是写方向的重定向，不能用在輸入端
            process.getOutputStream().close();
        }
        boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        int elapsedMs = (int) ((System.nanoTime() - start) / 1_000_000);
        if (!finished) {
            // 杀进程树，否则学生代码 fork 的子进程会留在机器上
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            process.waitFor(2, TimeUnit.SECONDS);
            return new ExecResult(-1, true, elapsedMs);
        }
        return new ExecResult(process.exitValue(), false, elapsedMs);
    }

    private String readCapped(Path file, int maxBytes) {
        if (file == null || !Files.exists(file)) {
            return "";
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] bytes = in.readNBytes(maxBytes);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return "";
        }
    }

    /** 判题输出比较：统一换行、去掉每行尾随空白、忽略末尾空行。 */
    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String unified = text.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = unified.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line.stripTrailing()).append('\n');
        }
        String result = sb.toString();
        while (result.endsWith("\n")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String trim(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…(已截断)";
    }

    /** 供测试/排查：返回实际要执行的命令（不执行）。 */
    public List<String> describeCommand(ProblemRepository.ProblemRow problem) {
        return buildRunCommand(problem, "java".equals(problem.language()));
    }

    public boolean isPosix() {
        return !windows;
    }

    public String pythonCommand() {
        return pythonCmd();
    }

}
