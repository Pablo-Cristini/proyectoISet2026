package com.instituto.siga.service.interfaces;

import com.instituto.siga.dto.request.MateriaRequest;
import com.instituto.siga.dto.request.PlanMateriaRequest;
import com.instituto.siga.dto.response.MateriaResponse;
import com.instituto.siga.dto.response.PlanMateriaResponse;

import java.util.List;

public interface MateriaService {

    List<MateriaResponse> listar(Long carreraId, Long planId, Integer anio);

    MateriaResponse obtenerPorId(Long id);

    MateriaResponse crear(MateriaRequest request);

    MateriaResponse actualizar(Long id, MateriaRequest request);

    void darDeBaja(Long id);

    void asociarAPlan(Long materiaId, Long planId, PlanMateriaRequest request);

    /** Cambia año / cuatrimestre de una materia dentro del plan (mismas validaciones por modalidad que al asociar). */
    void actualizarAsociacion(Long materiaId, Long planId, PlanMateriaRequest request);

    /** Quita la materia del plan (no la borra). */
    void desasociarDePlan(Long materiaId, Long planId);

    /** Malla del plan: cada materia con su año, cuatrimestre (null si es anual) y modalidad. */
    List<PlanMateriaResponse> listarDelPlan(Long planId);
}
