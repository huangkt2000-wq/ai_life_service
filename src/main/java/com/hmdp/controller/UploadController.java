package com.hmdp.controller;

import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.Result;
import com.hmdp.service.IMinioService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件上传控制器
 * 使用 MinIO 存储文件
 */
@Slf4j
@RestController
@RequestMapping("upload")
@RequiredArgsConstructor
public class UploadController {

    private final IMinioService minioService;

    /**
     * 上传博客图片
     */
    @PostMapping("blog")
    public Result uploadImage(@RequestParam("file") MultipartFile image) {
        try {
            if (image == null || image.isEmpty()) {
                return Result.fail("请选择要上传的文件");
            }

            // 上传到 MinIO，存储在 blogs 文件夹
            String fileUrl = minioService.uploadFile(image, "blogs");

            log.debug("文件上传成功: {}", fileUrl);

            // 返回文件访问 URL
            return Result.ok(fileUrl);

        } catch (Exception e) {
            log.error("文件上传失败", e);
            return Result.fail("文件上传失败: " + e.getMessage());
        }
    }

    /**
     * 删除博客图片
     */
    @GetMapping("/blog/delete")
    public Result deleteBlogImg(@RequestParam("name") String filename) {
        if (StrUtil.isBlank(filename)) {
            return Result.fail("文件名不能为空");
        }

        // 从 URL 中提取 objectName（去掉 bucket 前缀）
        // URL 格式: endpoint/bucket/objectName
        String objectName = filename;

        // 如果传入的是完整 URL，需要提取路径
        if (filename.startsWith("http://") || filename.startsWith("https://")) {
            // 从 URL 中提取 bucketName 之后的部分
            int bucketIndex = filename.indexOf("/hmdp/");
            if (bucketIndex > 0) {
                objectName = filename.substring(bucketIndex + 6); // "/hmdp/" 长度为 6
            } else {
                // 尝试其他格式
                String[] parts = filename.split("/");
                if (parts.length >= 4) {
                    // parts: http:, , host, bucket, objectName...
                    StringBuilder sb = new StringBuilder();
                    for (int i = 4; i < parts.length; i++) {
                        sb.append(parts[i]);
                        if (i < parts.length - 1) {
                            sb.append("/");
                        }
                    }
                    objectName = sb.toString();
                }
            }
        }

        try {
            minioService.deleteFile(objectName);
            return Result.ok();
        } catch (Exception e) {
            log.error("文件删除失败: {}", filename, e);
            return Result.fail("文件删除失败");
        }
    }
}