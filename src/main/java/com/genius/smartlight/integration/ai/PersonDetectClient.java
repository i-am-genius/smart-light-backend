package com.genius.smartlight.integration.ai;

import com.genius.smartlight.common.ServiceException;
import com.genius.smartlight.vo.ai.PersonDetectRespVO;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
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

@Component
public class PersonDetectClient {

    private final RestTemplate restTemplate;

    @Value("${ai.flow.url}")
    private String flowUrl;

    public PersonDetectClient(@Qualifier("aiRestTemplate") RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public PersonDetectRespVO detect(MultipartFile file) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);

            HttpEntity<byte[]> requestEntity = new HttpEntity<>(file.getBytes(), headers);

            ResponseEntity<PersonDetectRespVO> response = restTemplate.exchange(
                    flowUrl,
                    HttpMethod.POST,
                    requestEntity,
                    PersonDetectRespVO.class
            );

            if (response.getBody() == null) {
                throw new ServiceException("人流检测服务返回为空");
            }
            return response.getBody();
        } catch (HttpStatusCodeException e) {
            throw new ServiceException("人流检测服务请求失败：" + e.getStatusCode());
        } catch (ResourceAccessException e) {
            throw new ServiceException("人流检测服务连接超时或不可用，请稍后重试");
        } catch (Exception e) {
            throw new ServiceException("调用人流检测服务失败：" + e.getMessage());
        }
    }
}
