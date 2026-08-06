package com.genius.smartlight.integration.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genius.smartlight.common.ServiceException;
import com.genius.smartlight.vo.ai.PersonDetectRespVO;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServiceUnavailable;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PersonDetectClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void deserializesMetadataOnlyDetectionResponse() throws Exception {
        String json = """
                {
                  "count":1,
                  "confidence":0.91,
                  "timestamp":"2026-07-28 12:00:00",
                  "processing_time":18.4,
                  "image_width":640,
                  "image_height":480,
                  "detections":[{
                    "x1":10.0,"y1":20.0,"x2":110.0,"y2":220.0,
                    "confidence":0.91,"class_id":0
                  }]
                }
                """;

        PersonDetectRespVO value =
                objectMapper.readValue(json, PersonDetectRespVO.class);

        assertThat(value.getAnnotatedImageBase64()).isNull();
        assertThat(value.getImageWidth()).isEqualTo(640);
        assertThat(value.getImageHeight()).isEqualTo(480);
        assertThat(value.getDetections()).hasSize(1);
        assertThat(value.getDetections().get(0).getConfidence()).isEqualTo(0.91);
        assertThat(value.getDetections().get(0).getClassId()).isZero();
    }

    @Test
    void appendsImageFlagWithoutDiscardingExistingQuery() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        PersonDetectClient client =
                client(
                        restTemplate,
                        "http://localhost/detect_binary?source=cam&include_image=true"
                );

        server.expect(ExpectedCount.once(),
                        requestTo("http://localhost/detect_binary?source=cam&include_image=false"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        """
                        {"count":0,"detections":[]}
                        """,
                        MediaType.APPLICATION_JSON
                ));

        client.detect(imageFile(), false);

        server.verify();
    }

    @Test
    void legacyCallAlsoRequestsMetadataOnlyResponse() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        PersonDetectClient client =
                client(restTemplate, "http://localhost/detect_binary");

        server.expect(ExpectedCount.once(),
                        requestTo("http://localhost/detect_binary?include_image=false"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        """
                        {"count":0,"detections":[]}
                        """,
                        MediaType.APPLICATION_JSON
                ));

        client.detect(imageFile());

        server.verify();
    }

    @Test
    void mapsServiceUnavailableToBusyWithoutRetry() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        PersonDetectClient client =
                client(restTemplate, "http://localhost/detect_binary");

        server.expect(ExpectedCount.once(),
                        requestTo("http://localhost/detect_binary?include_image=false"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServiceUnavailable());

        assertThatThrownBy(() -> client.detect(imageFile(), false))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("人流检测服务")
                .hasMessageContaining("繁忙");

        server.verify();
    }

    private PersonDetectClient client(RestTemplate restTemplate, String url) {
        PersonDetectClient client = new PersonDetectClient(restTemplate);
        ReflectionTestUtils.setField(client, "flowUrl", url);
        return client;
    }

    private MockMultipartFile imageFile() {
        return new MockMultipartFile(
                "image",
                "sample.jpg",
                MediaType.IMAGE_JPEG_VALUE,
                new byte[]{1, 2, 3}
        );
    }
}
