package com.genius.smartlight.opsadmin;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpsAdminAuthFilterTest {

    @Test
    void doFilter_acceptsOpsAdminTokenFromQueryForImageRequests() throws ServletException, IOException {
        OpsAdminTokenService tokenService = mock(OpsAdminTokenService.class);
        OpsAdminAuthFilter filter = new OpsAdminAuthFilter(tokenService);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET",
                "/ops-admin/gallery/images/file"
        );
        request.setParameter("token", "ops-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        when(tokenService.parseToken("ops-token"))
                .thenReturn(new OpsAdminPrincipal("admin", "OPS_ADMIN"));

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isNotEqualTo(401);
        assertThat(chain.getRequest()).isSameAs(request);
        verify(tokenService).parseToken("ops-token");
    }
}
