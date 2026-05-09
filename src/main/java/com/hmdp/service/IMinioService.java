package com.hmdp.service;

import org.springframework.web.multipart.MultipartFile;

/**
 * MinIO 文件存储服务接口
 */
public interface IMinioService {

    /**
     * 上传文件到 MinIO
     * @param file 文件
     * @param folder 文件夹名称（如 blogs、avatars）
     * @return 文件访问 URL
     */
    String uploadFile(MultipartFile file, String folder);

    /**
     * 删除文件
     * @param objectName 对象名称（路径）
     */
    void deleteFile(String objectName);

    /**
     * 获取文件访问 URL
     * @param objectName 对象名称
     * @return 文件 URL
     */
    String getFileUrl(String objectName);
}