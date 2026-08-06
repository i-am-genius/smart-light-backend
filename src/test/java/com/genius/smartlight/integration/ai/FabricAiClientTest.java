package com.genius.smartlight.integration.ai;

import com.genius.smartlight.common.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServiceUnavailable;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class FabricAiClientTest {

    @Test
    void sendsSharedArchiveIdentityAndDisablesPreviewInMultipart() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        FabricAiClient client = client(restTemplate, "http://localhost/predict");
        MockMultipartFile file = imageFile();

        server.expect(ExpectedCount.once(), requestTo("http://localhost/predict"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> {
                    assertThat(request.getHeaders().getContentType())
                            .isNotNull()
                            .satisfies(contentType ->
                                    assertThat(contentType.isCompatibleWith(MediaType.MULTIPART_FORM_DATA))
                                            .isTrue());
                    String body = ((MockClientHttpRequest) request)
                            .getBodyAsString(StandardCharsets.UTF_8);
                    assertThat(body)
                            .contains("name=\"chipId\"", "lamp-1")
                            .contains("name=\"archiveId\"", "archive-1")
                            .contains("name=\"includePreview\"", "false");
                })
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.recognize(file, "lamp-1", "archive-1", false);

        server.verify();
    }

    @Test
    void legacyCallAlsoDisablesPreview() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        FabricAiClient client = client(restTemplate, "http://localhost/predict");

        server.expect(ExpectedCount.once(), requestTo("http://localhost/predict"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> {
                    String body = ((MockClientHttpRequest) request)
                            .getBodyAsString(StandardCharsets.UTF_8);
                    assertThat(body)
                            .contains("name=\"includePreview\"")
                            .contains("false");
                })
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.recognize(imageFile(), "lamp-1");

        server.verify();
    }

    @Test
    void mapsServiceUnavailableToBusyWithoutRetry() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        FabricAiClient client = client(restTemplate, "http://localhost/predict");

        server.expect(ExpectedCount.once(), requestTo("http://localhost/predict"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServiceUnavailable());

        assertThatThrownBy(() ->
                client.recognize(imageFile(), "lamp-1", "archive-1", false))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("面料识别服务")
                .hasMessageContaining("繁忙");

        server.verify();
    }

    private FabricAiClient client(RestTemplate restTemplate, String url) {
        FabricAiClient client = new FabricAiClient(restTemplate);
        ReflectionTestUtils.setField(client, "fabricUrl", url);
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
