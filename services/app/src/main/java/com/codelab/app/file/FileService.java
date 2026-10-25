package com.codelab.app.file;

import com.codelab.app.common.ApiException;
import com.codelab.app.org.OrgRepository;
import com.codelab.app.problem.ProblemRepository;
import com.codelab.app.storage.FileStorage;
import com.codelab.app.storage.StorageException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** 题目图片上传/回显（PRD §14.3）：MIME 白名单 + 5 MB 上限 + 归属校验。 */
@Service
public class FileService {

    public record UploadResult(UUID id, String url, String mime, int size) {
    }

    public record FileContent(byte[] bytes, String mime) {
    }

    private static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final Map<String, String> ALLOWED = Map.of(
            "image/png", "png",
            "image/jpeg", "jpg",
            "image/gif", "gif",
            "image/webp", "webp");
    private static final List<String> PURPOSES = List.of("problem_image");

    private final FileRepository files;
    private final FileStorage storage;
    private final ProblemRepository problems;
    private final OrgRepository org;

    public FileService(FileRepository files, FileStorage storage, ProblemRepository problems, OrgRepository org) {
        this.files = files;
        this.storage = storage;
        this.problems = problems;
        this.org = org;
    }

    public UploadResult upload(UUID teacherId, UUID problemId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.validation("文件为空");
        }
        if (problemId == null) {
            throw ApiException.validation("problemId 不能为空");
        }
        var problem = problems.findById(problemId).orElseThrow(() -> ApiException.notFound("题目不存在"));
        if (!org.teacherTeachesCourse(teacherId, problem.courseId())) {
            throw ApiException.notFound("题目不存在");
        }
        if (file.getSize() > MAX_BYTES) {
            throw ApiException.payloadTooLarge("单张图片不能超过 5 MB");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (Exception ex) {
            throw ApiException.validation("读取上传内容失败");
        }
        if (bytes.length > MAX_BYTES) {
            throw ApiException.payloadTooLarge("单张图片不能超过 5 MB");
        }
        // 类型以文件内容（魔数）为准：浏览器/脚本给的 Content-Type 不可靠
        String detected = detectImageType(bytes);
        if (detected == null) {
            throw ApiException.validation("只允许上传 PNG/JPEG/GIF/WebP 图片");
        }
        String ext = detected;
        String mime = ALLOWED.entrySet().stream()
                .filter(e -> e.getValue().equals(detected))
                .map(java.util.Map.Entry::getKey)
                .findFirst()
                .orElseThrow();
        String declared = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        if (!declared.isBlank() && !declared.equals(mime) && !declared.equals("application/octet-stream")) {
            throw ApiException.validation("文件内容与声明的类型不符（声明 " + declared + "，实际 " + mime + "）");
        }
        String objectKey = "problem/" + problemId + "/" + UUID.randomUUID() + "." + ext;
        try {
            storage.put(objectKey, bytes, mime);
        } catch (StorageException ex) {
            throw new StorageException("图片保存失败：" + ex.getMessage(), ex);
        }
        UUID id = files.insert(teacherId, problemId, PURPOSES.get(0), objectKey,
                trimName(file.getOriginalFilename()), mime, bytes.length);
        return new UploadResult(id, "/api/files/" + id, mime, bytes.length);
    }

    /** 回显：文件所有者、或该题对该学生可见、或该课程由该教师任课。其余一律 404。 */
    public FileContent load(UUID requesterId, String role, UUID fileId) {
        FileRepository.FileRow row = files.findById(fileId)
                .orElseThrow(() -> ApiException.notFound("文件不存在"));
        boolean allowed = row.ownerId().equals(requesterId);
        if (!allowed && row.problemId() != null) {
            if ("student".equals(role)) {
                allowed = problems.isVisibleToStudent(row.problemId(), requesterId);
            } else if ("teacher".equals(role)) {
                allowed = problems.findById(row.problemId())
                        .map(p -> org.teacherTeachesCourse(requesterId, p.courseId()))
                        .orElse(false);
            } else if ("admin".equals(role)) {
                allowed = true;
            }
        }
        if (!allowed) {
            throw ApiException.notFound("文件不存在");
        }
        try {
            return new FileContent(storage.get(row.objectKey()), row.mime());
        } catch (StorageException ex) {
            throw ApiException.notFound("文件内容不存在");
        }
    }

    private static String trimName(String name) {
        if (name == null) {
            return null;
        }
        return name.length() <= 200 ? name : name.substring(0, 200);
    }

    /** 魔数识别图片类型；不是白名单图片返回 null（避免把 HTML/SVG 伪造成图片塞进存储）。 */
    private static String detectImageType(byte[] bytes) {
        if (bytes.length < 12) {
            return null;
        }
        if ((bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
            return "png";
        }
        if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8) {
            return "jpg";
        }
        if (bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F') {
            return "gif";
        }
        if (bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return "webp";
        }
        return null;
    }
}
