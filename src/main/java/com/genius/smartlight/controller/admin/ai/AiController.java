package com.genius.smartlight.controller.admin.ai;

import com.genius.smartlight.common.CommonResult;
import com.genius.smartlight.common.FileDownloadUtil;
import com.genius.smartlight.common.MediaTypeUtil;
import com.genius.smartlight.service.ai.AiService;
import com.genius.smartlight.service.ai.FabricArchiveService;
import com.genius.smartlight.service.device.GarmentSourceResultService;
import com.genius.smartlight.vo.ai.FabricArchiveDeleteRespVO;
import com.genius.smartlight.vo.ai.FabricArchivePageRespVO;
import com.genius.smartlight.vo.ai.FabricRecognizeRespVO;
import com.genius.smartlight.vo.ai.PersonDetectRespVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;

@Tag(name = "AI识别接口", description = "服装面料识别、人流检测和识别图片留档管理接口")
@RestController
@RequestMapping("/admin/ai")
@RequiredArgsConstructor
public class AiController {

    private final AiService aiService;
    private final FabricArchiveService fabricArchiveService;
    private final GarmentSourceResultService garmentSourceResultService;

    @Operation(summary = "服装识别留档相册", description = "分页查询当前店铺服装识别留档图片，type 支持 original、annotated、combined")
    @GetMapping("/fabric-archive")
    public CommonResult<FabricArchivePageRespVO> fabricArchive(
            @Parameter(description = "图片类型", example = "combined")
            @RequestParam(defaultValue = "combined") String type,
            @Parameter(description = "页码", example = "1")
            @RequestParam(defaultValue = "1") Integer page,
            @Parameter(description = "每页数量", example = "30")
            @RequestParam(defaultValue = "30") Integer pageSize) throws IOException {
        return CommonResult.success(fabricArchiveService.listArchive(type, page, pageSize));
    }

    @Operation(summary = "删除服装识别留档图片", description = "按 filename 或 baseName 删除同一组留档图片，需校验当前店铺对该设备的所有权")
    @DeleteMapping("/fabric-archive")
    public CommonResult<FabricArchiveDeleteRespVO> deleteFabricArchive(
            @Parameter(description = "留档文件名", example = "ABC123456_20260414_103000_A1B2C3D4_combined.jpg")
            @RequestParam(required = false) String filename,
            @Parameter(description = "留档基础文件名")
            @RequestParam(required = false) String baseName) throws IOException {
        return CommonResult.success(fabricArchiveService.deleteArchiveGroup(filename, baseName));
    }

    @Operation(summary = "读取服装识别留档图片", description = "按当前店铺设备归属校验后返回留档图片")
    @GetMapping("/fabric-archive/file")
    public ResponseEntity<Resource> fabricArchiveFile(
            @RequestParam(defaultValue = "combined") String type,
            @RequestParam String filename) {
        Path file = fabricArchiveService.getArchiveFile(type, filename);
        return FileDownloadUtil.inlineFile(file, MediaTypeUtil.resolveImageMediaType(file.getFileName().toString()));
    }

    @Operation(
            summary = "服装面料识别",
            description = "上传图片文件，chipId 为可选参数。返回面料 label、confidence、推荐色温亮度等"
    )
    @PostMapping(value = "/fabric-recognize", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CommonResult<FabricRecognizeRespVO> fabricRecognize(
            @Parameter(description = "上传图片文件", required = true)
            @RequestPart("file") MultipartFile file,
            @Parameter(description = "芯片唯一ID，可选")
            @RequestParam(required = false) String chipId) {
        FabricRecognizeRespVO result = aiService.fabricRecognize(chipId, file);
        if (chipId != null && !chipId.isBlank()) {
            garmentSourceResultService.saveLatestResult(chipId, GarmentSourceResultService.PHONE);
        }
        return CommonResult.success(result);
    }

    private MediaType resolveImageMediaType(String filename) {
        return MediaTypeUtil.resolveImageMediaType(filename);
    }

    @Operation(
            summary = "人流检测",
            description = "上传图片文件，chipId 为可选参数。返回 count、confidence、timestamp 等"
    )
    @PostMapping(value = "/person-detect", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CommonResult<PersonDetectRespVO> personDetect(
            @Parameter(description = "上传图片文件", required = true)
            @RequestPart("file") MultipartFile file,
            @Parameter(description = "芯片唯一ID，可选")
            @RequestParam(required = false) String chipId) {
        return CommonResult.success(aiService.personDetect(chipId, file));
    }
}
