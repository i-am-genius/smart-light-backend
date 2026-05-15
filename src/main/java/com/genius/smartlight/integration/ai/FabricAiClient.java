package com.genius.smartlight.integration.ai;

import com.genius.smartlight.common.ServiceException;
import com.genius.smartlight.vo.ai.FabricRecognizeRespVO;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

@Component
public class FabricAiClient {

    private final RestTemplate restTemplate;

    @Value("${ai.fabric.url}")
    private String fabricUrl;

    public FabricAiClient(@Qualifier("aiRestTemplate") RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public FabricRecognizeRespVO recognize(MultipartFile file, String chipId) {
        try {
            ByteArrayResource resource = new ByteArrayResource(file.getBytes()) {
                @Override
                public String getFilename() {
                    return file.getOriginalFilename() != null ? file.getOriginalFilename() : "image.jpg";
                }
            };

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("image", resource);
            if (chipId != null && !chipId.isBlank()) {
                body.add("chipId", chipId);
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

            ResponseEntity<FabricRecognizeRespVO> response = restTemplate.exchange(
                    fabricUrl,
                    HttpMethod.POST,
                    requestEntity,
                    FabricRecognizeRespVO.class
            );

            if (response.getBody() == null) {
                throw new ServiceException("面料识别服务返回为空");
            }
            return response.getBody();
        } catch (HttpStatusCodeException e) {
            throw new ServiceException("面料识别服务请求失败：" + e.getStatusCode());
        } catch (ResourceAccessException e) {
            throw new ServiceException("面料识别服务连接超时或不可用，请稍后重试");
        } catch (Exception e) {
            throw new ServiceException("调用面料识别服务失败：" + e.getMessage());
        }
    }
}
