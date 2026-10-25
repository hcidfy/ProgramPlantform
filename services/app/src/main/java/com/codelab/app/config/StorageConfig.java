package com.codelab.app.config;

import com.codelab.app.storage.FileStorage;
import com.codelab.app.storage.LocalFileStorage;
import com.codelab.app.storage.MinioFileStorage;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 按 app.storage.type 选实现（PRD §14.1 D2）。 */
@Configuration
public class StorageConfig {

    private static final Logger log = LoggerFactory.getLogger(StorageConfig.class);

    @Bean
    public FileStorage fileStorage(AppProperties props) {
        AppProperties.Storage storage = props.storage();
        String type = storage == null || storage.type() == null ? "local" : storage.type().trim().toLowerCase();
        if ("minio".equals(type)) {
            AppProperties.Storage.Minio minio = storage.minio();
            if (minio == null || minio.accessKey() == null || minio.accessKey().isBlank()) {
                throw new IllegalStateException("APP_STORAGE_TYPE=minio 时必须配置 MINIO_ACCESS_KEY / MINIO_SECRET_KEY");
            }
            MinioFileStorage impl = new MinioFileStorage(minio.endpoint(), minio.bucket(), minio.accessKey(),
                    minio.secretKey());
            impl.ensureBucket();
            log.info("文件存储: {}", impl.describe());
            return impl;
        }
        String dir = storage == null || storage.localDir() == null || storage.localDir().isBlank()
                ? "storage" : storage.localDir();
        LocalFileStorage impl = new LocalFileStorage(Path.of(dir));
        log.warn("文件存储: {}（local 仅用于开发；生产必须 APP_STORAGE_TYPE=minio，见 PRD §14.1 D2）", impl.describe());
        return impl;
    }
}
