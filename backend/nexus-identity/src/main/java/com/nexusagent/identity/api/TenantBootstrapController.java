package com.nexusagent.identity.api;


import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tenants")
public class TenantBootstrapController {

    private final TenantBootstrapService tenantBootstrapService;

    public TenantBootstrapController(
            TenantBootstrapService tenantBootstrapService
    ) {
        this.tenantBootstrapService = tenantBootstrapService;
    }

    @PostMapping("/bootstrap")
    public ResponseEntity<BootstrapTenantResponse> bootstrap(
            @Valid @RequestBody BootstrapTenantRequest request
    ) {
        BootstrapTenantResponse response = tenantBootstrapService.bootstrap(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }
}
