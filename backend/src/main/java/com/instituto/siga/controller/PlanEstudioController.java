package com.instituto.siga.controller;

import com.instituto.siga.dto.request.PlanEstudioRequest;
import com.instituto.siga.dto.response.PlanEstudioResponse;
import com.instituto.siga.dto.response.PlanMateriaResponse;
import com.instituto.siga.service.interfaces.MateriaService;
import com.instituto.siga.service.interfaces.PlanEstudioService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/planes-estudio")
@RequiredArgsConstructor
public class PlanEstudioController {

    private final PlanEstudioService planEstudioService;
    private final MateriaService materiaService;

    /** Malla del plan con año, cuatrimestre (null = anual) y modalidad de cada materia. */
    @GetMapping("/{id}/materias")
    public ResponseEntity<List<PlanMateriaResponse>> malla(@PathVariable Long id) {
        return ResponseEntity.ok(materiaService.listarDelPlan(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<PlanEstudioResponse> actualizar(@PathVariable Long id,
                                                           @Valid @RequestBody PlanEstudioRequest request) {
        return ResponseEntity.ok(planEstudioService.actualizar(id, request));
    }
}
