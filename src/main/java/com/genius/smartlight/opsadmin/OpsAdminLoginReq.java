package com.genius.smartlight.opsadmin;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class OpsAdminLoginReq {
    @NotBlank
    private String username;

    @NotBlank
    private String password;
}
