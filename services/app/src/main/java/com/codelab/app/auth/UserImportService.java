package com.codelab.app.auth;

import com.codelab.app.audit.AuditActions;
import com.codelab.app.audit.AuditService;
import com.codelab.app.common.ApiException;
import com.codelab.app.org.OrgRepository;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 名单导入（FR-ACC-01）：CSV 列 = email,display_name,role,class_name[,password]。
 * 首行若以 email 开头视为表头跳过。逐行独立成败，失败行给出原因。
 */
@Service
public class UserImportService {

    public record CreatedUser(int line, String email, String role, String initialPassword) {
    }

    public record FailedLine(int line, String reason) {
    }

    public record ImportResult(int created, List<CreatedUser> createdUsers, List<FailedLine> failed) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALNUM = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final AuthService auth;
    private final OrgRepository org;
    private final AuditService audit;

    public UserImportService(AuthService auth, OrgRepository org, AuditService audit) {
        this.auth = auth;
        this.org = org;
        this.audit = audit;
    }

    public ImportResult importCsv(UUID actorId, String csv) {
        if (csv == null || csv.isBlank()) {
            throw ApiException.validation("CSV 内容为空");
        }
        List<CreatedUser> created = new ArrayList<>();
        List<FailedLine> failed = new ArrayList<>();
        String[] lines = csv.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        boolean firstDataLine = true;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            if (firstDataLine && line.toLowerCase().startsWith("email")) {
                firstDataLine = false;
                continue;
            }
            firstDataLine = false;
            int lineNo = i + 1;
            String[] cols = line.split(",", -1);
            if (cols.length < 2) {
                failed.add(new FailedLine(lineNo, "列数不足，至少需要 email,display_name"));
                continue;
            }
            String email = cols[0].trim();
            String displayName = cols[1].trim();
            String role = cols.length > 2 && !cols[2].isBlank() ? cols[2].trim().toLowerCase() : "student";
            String className = cols.length > 3 ? cols[3].trim() : "";
            String password = cols.length > 4 ? cols[4].trim() : "";

            if (email.isEmpty() || !email.contains("@")) {
                failed.add(new FailedLine(lineNo, "邮箱格式不正确：" + email));
                continue;
            }
            if (displayName.isEmpty()) {
                failed.add(new FailedLine(lineNo, "姓名为空"));
                continue;
            }
            if (!"student".equals(role) && !"teacher".equals(role)) {
                failed.add(new FailedLine(lineNo, "角色只能是 student 或 teacher：" + role));
                continue;
            }
            UUID classId = null;
            if (!className.isEmpty()) {
                if (!"student".equals(role)) {
                    failed.add(new FailedLine(lineNo, "只有学生可以指定班级"));
                    continue;
                }
                var row = org.findClassByName(className).orElse(null);
                if (row == null) {
                    failed.add(new FailedLine(lineNo, "班级不存在：" + className));
                    continue;
                }
                classId = row.id();
            }
            String initialPassword = password.isEmpty() ? generatePassword() : password;
            try {
                AuthService.UserView user = auth.createUser(actorId, email, displayName, role, initialPassword,
                        AuditActions.ADMIN_IMPORT_USERS);
                if (classId != null) {
                    org.upsertEnrollment(user.id(), classId);
                }
                created.add(new CreatedUser(lineNo, user.email(), role, initialPassword));
            } catch (ApiException ex) {
                failed.add(new FailedLine(lineNo, ex.getMessage()));
            }
        }
        audit.record(actorId, AuditActions.ADMIN_IMPORT_USERS, "users",
                Map.of("created", created.size(), "failed", failed.size()));
        return new ImportResult(created.size(), created, failed);
    }

    /** 生成 11 位初始密码，构造上保证同时含字母与数字（Q6：管理员代发初始密码）。 */
    private static String generatePassword() {
        StringBuilder sb = new StringBuilder("Cl");
        for (int i = 0; i < 8; i++) {
            sb.append(ALNUM.charAt(RANDOM.nextInt(ALNUM.length())));
        }
        return sb.append(RANDOM.nextInt(10)).toString();
    }
}
