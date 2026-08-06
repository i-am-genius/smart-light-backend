package com.genius.smartlight.integration.ai;

import com.genius.smartlight.common.ServiceException;
import com.genius.smartlight.vo.ai.PersonDetectRespVO;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

@Component
public class PersonDetectClient {

    private final RestTemplate restTemplate;

    @Value("${ai.flow.url}")
    private String flowUrl;

    public PersonDetectClient(@Qualifier("flowAiRestTemplate") RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public PersonDetectRespVO detect(MultipartFile file) {
        return detect(file, false);
    }

    public PersonDetectRespVO detect(MultipartFile file, boolean includeImage) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);

            InputStreamResource resource = new InputStreamResource(file.getInputStream()) {
                @Override
                public String getFilename() {
                    return file.getOriginalFilename() != null ? file.getOriginalFilename() : "image.jpg";
                }

                @Override
                public long contentLength() {
                    return file.getSize();
                }
            };

            HttpEntity<InputStreamResource> requestEntity = new HttpEntity<>(resource, headers);
            URI requestUri = UriComponentsBuilder.fromUriString(flowUrl)
                    .replaceQueryParam("include_image", includeImage)
                    .build(true)
                    .toUri();

            ResponseEntity<PersonDetectRespVO> response = restTemplate.exchange(
                    requestUri,
                    HttpMethod.POST,
                    requestEntity,
                    PersonDetectRespVO.class
            );

            if (response.getBody() == null) {
                throw new ServiceException("人流检测服务返回为空");
            }
            return response.getBody();
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode().value() == 503) {
                throw new ServiceException("人流检测服务繁忙，请稍后重试");
            }
            throw new ServiceException("人流检测服务请求失败：" + e.getStatusCode());
        } catch (ResourceAccessException e) {
            throw new ServiceException("人流检测服务连接超时或不可用，请稍后重试");
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("调用人流检测服务失败：" + e.getMessage());
        }
    }
}
