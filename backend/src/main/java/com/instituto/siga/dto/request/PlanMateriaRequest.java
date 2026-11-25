package com.instituto.siga.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** cuatrimestre: 1 o 2 si la materia es CUATRIMESTRAL; null (u omitido) si es ANUAL. Lo valida MateriaServiceImpl
 * segun la modalidad de la materia. */
public record PlanMateriaRequest(
        @Min(1) int anio,
        @Min(1) @Max(2) Integer cuatrimestre) {
}
