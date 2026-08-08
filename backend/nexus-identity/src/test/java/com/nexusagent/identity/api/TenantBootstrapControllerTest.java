package com.nexusagent.identity.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TenantBootstrapController.class)
@Import(IdentityExceptionHandler.class)
class TenantBootstrapControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TenantBootstrapService tenantBootstrapService;

    @Test
    void shouldReturnCreatedTenant() throws Exception {
        when(tenantBootstrapService.bootstrap(any()))
                .thenReturn(new BootstrapTenantResponse(
                        "101",
                        "102",
                        "103"
                ));

        mockMvc.perform(post("/api/v1/tenants/bootstrap")
                        .contentType("application/json")
                        .content("""
                                {
                                  "tenantCode": "acme-corp",
                                  "tenantName": "Acme Corporation",
                                  "adminUsername": "admin",
                                  "adminEmail": "admin@acme.example",
                                  "adminPassword": "StrongPassword123!"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(
                        "application/json"
                ))
                .andExpect(jsonPath("$.tenantId").value("101"))
                .andExpect(jsonPath("$.adminUserId").value("102"))
                .andExpect(jsonPath("$.adminRoleId").value("103"));
    }

    @Test
    void shouldReturnBadRequestForInvalidRequest() throws Exception {
        mockMvc.perform(post("/api/v1/tenants/bootstrap")
                        .contentType("application/json")
                        .content("""
                                {
                                  "tenantCode": "INVALID CODE",
                                  "tenantName": "",
                                  "adminUsername": "a",
                                  "adminEmail": "invalid",
                                  "adminPassword": "short"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode")
                        .value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.tenantCode").exists())
                .andExpect(jsonPath("$.errors.adminEmail").exists())
                .andExpect(jsonPath("$.errors.adminPassword").exists());
    }

    @Test
    void shouldReturnConflictForDuplicateTenant() throws Exception {
        when(tenantBootstrapService.bootstrap(any()))
                .thenThrow(
                        new TenantCodeAlreadyExistsException(
                                "acme-corp"
                        )
                );

        mockMvc.perform(post("/api/v1/tenants/bootstrap")
                        .contentType("application/json")
                        .content("""
                                {
                                  "tenantCode": "acme-corp",
                                  "tenantName": "Acme Corporation",
                                  "adminUsername": "admin",
                                  "adminEmail": "admin@acme.example",
                                  "adminPassword": "StrongPassword123!"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode")
                        .value("TENANT_CODE_ALREADY_EXISTS"))
                .andExpect(jsonPath("$.tenantCode")
                        .value("acme-corp"));
    }
}