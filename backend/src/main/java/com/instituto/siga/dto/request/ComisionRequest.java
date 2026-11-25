package com.instituto.siga.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record ComisionRequest(
        @NotNull Long materiaId,
        /** Opcional: si se omite y la materia pertenece a una sola carrera, se usa esa. */
        Long carreraId,
        @NotBlank String nombre,
        @NotBlank String periodoLectivo,
        @Min(1) int cupoMaximo,
        String profesor,
        @Valid List<HorarioRequest> horarios) {
}
