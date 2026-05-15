package com.genius.smartlight.opsadmin;

import com.genius.smartlight.common.CommonResult;
import com.genius.smartlight.vo.ai.FabricArchiveDeleteRespVO;
import com.genius.smartlight.vo.ai.FabricArchivePageRespVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

@RestController
@RequestMapping("/ops-admin/gallery")
@RequiredArgsConstructor
public class OpsAdminGalleryController {

    private final OpsAdminGalleryService galleryService;

    @GetMapping("/images")
    public CommonResult<FabricArchivePageRespVO> listImages(
            @RequestParam(defaultValue = "combined") String type,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "30") int pageSize) throws IOException {
        return CommonResult.success(galleryService.listImages(type, page, pageSize));
    }

    @DeleteMapping("/images")
    public CommonResult<FabricArchiveDeleteRespVO> deleteImage(
            @RequestParam(required = false) String filename,
            @RequestParam(required = false) String baseName) throws IOException {
        return CommonResult.success(galleryService.deleteImage(filename, baseName));
    }
}
