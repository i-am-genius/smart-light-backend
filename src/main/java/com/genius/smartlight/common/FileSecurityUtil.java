package com.genius.smartlight.common;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 文件安全工具类，统一路径遍历防护。
 */
public final class FileSecurityUtil {

    private FileSecurityUtil() {
    }

    /**
     * 在基础目录内安全解析用户输入路径，防止路径遍历攻击。
     *
     * @param baseDir   基础目录（应已 normalize + toAbsolutePath）
     * @param userInput 用户输入的子路径（可包含相对路径，但不能跳出 baseDir）
     * @return 解析后的绝对路径
     * @throws ServiceException 如果路径不合法或文件不存在
     */
    public static Path resolveSafe(Path baseDir, String userInput) {
        if (userInput == null || userInput.isBlank()) {
            throw new ServiceException("路径不能为空");
        }
        if (userInput.contains("..")) {
            throw new ServiceException("路径包含非法字符");
        }
        Path target = baseDir.resolve(userInput).normalize();
        if (!target.startsWith(baseDir)) {
            throw new ServiceException("路径越权");
        }
        return target;
    }

    /**
     * 安全解析并校验目标是一个存在的常规文件。
     */
    public static Path resolveExistingFile(Path baseDir, String userInput) {
        Path target = resolveSafe(baseDir, userInput);
        if (!Files.isRegularFile(target)) {
            throw new ServiceException("文件不存在");
        }
        return target;
    }
}
