package com.genius.smartlight.websocket.fabric;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

@Component
@RequiredArgsConstructor
public class FabricImageLiveNotifier {

    private final FabricImagePushService pushService;

    public boolean pushIfPresent(Long storeId,
                                 String chipId,
                                 Path archiveBaseDir,
                                 String annotatedFilename) {
        return pushIfPresent(storeId, chipId, archiveBaseDir, annotatedFilename, null);
    }

    public boolean pushIfPresent(Long storeId,
                                 String chipId,
                                 Path archiveBaseDir,
                                 String backendAnnotatedFilename,
                                 String aiAnnotatedPath) {
        if (storeId == null
                || chipId == null || chipId.isBlank()
                || archiveBaseDir == null) {
            return false;
        }
        Path annotatedRoot = archiveBaseDir.resolve("annotated").toAbsolutePath().normalize();
        for (String candidateValue : List.of(
                backendAnnotatedFilename == null ? "" : backendAnnotatedFilename,
                aiAnnotatedPath == null ? "" : aiAnnotatedPath
        )) {
            Path annotatedPath = resolveCandidate(archiveBaseDir, annotatedRoot, candidateValue);
            if (annotatedPath != null
                    && Files.isRegularFile(annotatedPath, LinkOption.NOFOLLOW_LINKS)) {
                pushService.pushLiveToStore(storeId, chipId, annotatedPath);
                return true;
            }
        }
        return false;
    }

    private Path resolveCandidate(Path archiveBaseDir,
                                  Path annotatedRoot,
                                  String candidateValue) {
        if (candidateValue == null || candidateValue.isBlank()) {
            return null;
        }
        try {
            Path supplied = Path.of(candidateValue.trim());
            Path candidate;
            if (supplied.isAbsolute()) {
                candidate = supplied.toAbsolutePath().normalize();
            } else if (supplied.getNameCount() == 1) {
                candidate = annotatedRoot.resolve(supplied).toAbsolutePath().normalize();
            } else if ("annotated".equals(supplied.getName(0).toString())) {
                candidate = archiveBaseDir.resolve(supplied).toAbsolutePath().normalize();
            } else {
                return null;
            }
            return annotatedRoot.equals(candidate.getParent()) ? candidate : null;
        } catch (InvalidPathException e) {
            return null;
        }
    }
}
