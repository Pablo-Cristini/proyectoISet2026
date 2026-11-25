package com.instituto.siga.dto.request;

import com.instituto.siga.entity.ModalidadCursada;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/** modalidad: CUATRIMESTRAL o ANUAL; si se omite es CUATRIMESTRAL (compatibilidad con clientes anteriores). */
public record MateriaRequest(
        @NotBlank String codigo,
        @NotBlank String nombre,
        @Min(1) int cargaHoraria,
        ModalidadCursada modalidad) {
}
