package com.instituto.siga.dto.response;

import java.time.LocalDateTime;

public record EstadoAcademicoResponse(
        Long alumnoId,
        Long materiaId,
        String materiaNombre,
        String estado,
        Integer nota,
        LocalDateTime fechaActualizacion,
        boolean excelSincronizado,
        String excelMensaje) {

    /** Copia con el resultado real de la escritura en el Excel (se conoce recien despues del commit). */
    public EstadoAcademicoResponse conExcel(Boolean sincronizado, String mensaje) {
        return new EstadoAcademicoResponse(alumnoId, materiaId, materiaNombre, estado, nota, fechaActualizacion,
                Boolean.TRUE.equals(sincronizado), mensaje);
    }
}
