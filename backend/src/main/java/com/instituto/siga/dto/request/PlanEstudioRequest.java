package com.instituto.siga.dto.request;

import com.instituto.siga.entity.PlanEstudio;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PlanEstudioRequest(
        @NotBlank
        @Size(max = PlanEstudio.LARGO_MAXIMO_VERSION, message = "La versión puede tener hasta 100 caracteres")
        String version,
        Boolean vigente) {
}
