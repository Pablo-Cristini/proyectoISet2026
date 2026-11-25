package com.instituto.siga.dto.response;

import java.util.List;

public record ComisionResponse(
        Long id,
        Long materiaId,
        String materiaNombre,
        Long carreraId,
        String carreraNombre,
        String nombre,
        String periodoLectivo,
        int cupoMaximo,
        String profesor,
        List<HorarioResponse> horarios) {
}
