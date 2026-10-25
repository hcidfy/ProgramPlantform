package com.codelab.app.file;

import com.codelab.app.security.SecurityUtils;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 题目图片：教师上传 → 题面 Markdown 里引用 /api/files/{id}（PRD §14.3）。 */
@RestController
public class FileController {

    private final FileService files;

    public FileController(FileService files) {
        this.files = files;
    }

    @PostMapping("/api/teacher/files")
    public ResponseEntity<FileService.UploadResult> upload(@RequestParam("file") MultipartFile file,
            @RequestParam("problemId") UUID problemId) {
        var result = files.upload(SecurityUtils.require().id(), problemId, file);
        return ResponseEntity.status(201).body(result);
    }

    @GetMapping("/api/files/{id}")
    public ResponseEntity<byte[]> download(@PathVariable UUID id) {
        var current = SecurityUtils.require();
        var content = files.load(current.id(), current.role(), id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.mime()))
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofMinutes(10)).cachePrivate())
                .body(content.bytes());
    }
}
