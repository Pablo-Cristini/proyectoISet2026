package com.instituto.siga.dto.response;

/** Una materia dentro de la malla de un plan: su ubicacion (año / cuatrimestre) y su modalidad.
 * cuatrimestre es null para las materias ANUALES. */
public record PlanMateriaResponse(
        Long planId,
        Long materiaId,
        String codigo,
        String nombre,
        int cargaHoraria,
        boolean activa,
        String modalidad,
        int anio,
        Integer cuatrimestre) {
}
