package com.genius.smartlight.opsadmin;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OpsAdminSystemStatusServiceTest {

    @Test
    void classifiesDeployedGunicornAndServerManagerProcesses() {
        var fabric = OpsAdminSystemStatusService.classifyProcess(
                "gunicorn",
                "/opt/miniconda/bin/gunicorn --bind 127.0.0.1:5011 fabric_wsgi:app",
                false
        );
        var flow = OpsAdminSystemStatusService.classifyProcess(
                "gunicorn",
                "/opt/miniconda/bin/gunicorn --bind 127.0.0.1:5000 flow_wsgi:app",
                false
        );
        var manager = OpsAdminSystemStatusService.classifyProcess(
                "java",
                "/usr/bin/java -jar /opt/server-manager/backend/server-manager.jar",
                false
        );

        assertThat(fabric.displayName()).isEqualTo("Fabric AI");
        assertThat(fabric.typeKey()).isEqualTo("ai");
        assertThat(flow.displayName()).isEqualTo("People Flow AI");
        assertThat(flow.typeKey()).isEqualTo("ai");
        assertThat(manager.displayName()).isEqualTo("Server Manager Backend");
        assertThat(manager.typeKey()).isEqualTo("manager");
    }

    @Test
    void checksFabricFlowAndServerManagerUsingTheirActualEndpoints() {
        RestTemplate healthTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(healthTemplate)
                .ignoreExpectOrder(true)
                .build();

        expectOk(server, "http://127.0.0.1:5011/health", HttpMethod.GET);
        expectOk(server, "http://127.0.0.1:5000/health", HttpMethod.GET);
        expectOk(server, "http://127.0.0.1:9080/actuator/health", HttpMethod.GET);
        expectOk(server, "https://archive.genius.show/", HttpMethod.HEAD);

        OpsAdminSystemStatusService service = new OpsAdminSystemStatusService(
                new RestTemplate(),
                healthTemplate,
                "",
                "",
                "https://archive.genius.show/",
                "http://127.0.0.1:9080/actuator/health",
                "http://127.0.0.1:5011/predict",
                "http://127.0.0.1:5000/detect_binary"
        );

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> services =
                (List<Map<String, Object>>) service.collect().get("services");

        assertThat(services)
                .extracting(row -> row.get("key"))
                .containsExactly(
                        "backend",
                        "fabric-ai",
                        "flow-ai",
                        "server-manager",
                        "nginx"
                );
        assertThat(serviceByKey(services, "fabric-ai").get("checkTarget"))
                .isEqualTo("http://127.0.0.1:5011/health");
        assertThat(serviceByKey(services, "flow-ai").get("checkTarget"))
                .isEqualTo("http://127.0.0.1:5000/health");
        assertThat(serviceByKey(services, "server-manager").get("checkTarget"))
                .isEqualTo("http://127.0.0.1:9080/actuator/health");
        assertThat(services)
                .allSatisfy(row -> assertThat(row.get("status")).isEqualTo("UP"));
        server.verify();
    }

    private static void expectOk(MockRestServiceServer server, String url, HttpMethod httpMethod) {
        server.expect(once(), requestTo(url))
                .andExpect(method(httpMethod))
                .andRespond(withSuccess("{\"status\":\"UP\"}", MediaType.APPLICATION_JSON));
    }

    private static Map<String, Object> serviceByKey(
            List<Map<String, Object>> services,
            String key
    ) {
        return services.stream()
                .filter(row -> key.equals(row.get("key")))
                .findFirst()
                .orElseThrow();
    }
}
