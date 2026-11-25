package com.instituto.siga.service.impl;

import com.instituto.siga.audit.AuditoriaPublisher;
import com.instituto.siga.dto.request.MateriaRequest;
import com.instituto.siga.dto.request.PlanMateriaRequest;
import com.instituto.siga.dto.response.MateriaResponse;
import com.instituto.siga.dto.response.PlanMateriaResponse;
import com.instituto.siga.entity.Materia;
import com.instituto.siga.entity.ModalidadCursada;
import com.instituto.siga.entity.PlanEstudio;
import com.instituto.siga.entity.PlanMateria;
import com.instituto.siga.exception.BusinessException;
import com.instituto.siga.exception.ConflictException;
import com.instituto.siga.exception.ResourceNotFoundException;
import com.instituto.siga.mapper.MateriaMapper;
import com.instituto.siga.repository.ComisionRepository;
import com.instituto.siga.repository.MateriaRepository;
import com.instituto.siga.repository.PlanEstudioRepository;
import com.instituto.siga.repository.PlanMateriaRepository;
import com.instituto.siga.security.SecurityUtils;
import com.instituto.siga.service.interfaces.MateriaService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class MateriaServiceImpl implements MateriaService {

    private final MateriaRepository materiaRepository;
    private final PlanEstudioRepository planEstudioRepository;
    private final PlanMateriaRepository planMateriaRepository;
    private final MateriaMapper materiaMapper;
    private final AuditoriaPublisher auditoriaPublisher;
    private final ComisionRepository comisionRepository;

    @Override
    @Transactional(readOnly = true)
    public List<MateriaResponse> listar(Long carreraId, Long planId, Integer anio) {
        return materiaRepository.buscar(carreraId, planId, anio).stream().map(materiaMapper::toResponse).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public MateriaResponse obtenerPorId(Long id) {
        return materiaMapper.toResponse(buscarOFallar(id));
    }

    @Override
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    @Transactional
    public MateriaResponse crear(MateriaRequest request) {
        if (materiaRepository.existsByCodigo(request.codigo())) {
            throw new ConflictException("MATERIA_DUPLICADA", "Ya existe una materia con ese código");
        }

        Materia materia = materiaMapper.toEntity(request);
        materia.setActiva(true);
        materia.setModalidad(modalidadDe(request));

        Materia guardada = materiaRepository.save(materia);
        auditoriaPublisher.publicar(SecurityUtils.usuarioActualId(), "MATERIA_CREADA", "Materia", guardada.getId(),
                Map.of("codigo", guardada.getCodigo(), "nombre", guardada.getNombre()));

        return materiaMapper.toResponse(guardada);
    }

    @Override
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    @Transactional
    public MateriaResponse actualizar(Long id, MateriaRequest request) {
        Materia materia = buscarOFallar(id);
        if (materiaRepository.existsByCodigoAndIdNot(request.codigo(), id)) {
            throw new ConflictException("MATERIA_DUPLICADA", "Ya existe una materia con ese código");
        }

        materia.setCodigo(request.codigo());
        materia.setNombre(request.nombre());
        materia.setCargaHoraria(request.cargaHoraria());
        cambiarModalidad(materia, modalidadDe(request));

        Materia guardada = materiaRepository.save(materia);
        auditoriaPublisher.publicar(SecurityUtils.usuarioActualId(), "MATERIA_ACTUALIZADA", "Materia", guardada.getId(),
                Map.of("codigo", guardada.getCodigo(), "nombre", guardada.getNombre()));

        return materiaMapper.toResponse(guardada);
    }

    @Override
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    @Transactional
    public void darDeBaja(Long id) {
        Materia materia = buscarOFallar(id);
        materia.setActiva(false);
        Materia guardada = materiaRepository.save(materia);
        auditoriaPublisher.publicar(SecurityUtils.usuarioActualId(), "MATERIA_BAJA", "Materia", guardada.getId(), Map.of());
    }

    @Override
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    @Transactional
    public void asociarAPlan(Long materiaId, Long planId, PlanMateriaRequest request) {
        Materia materia = buscarOFallar(materiaId);
        PlanEstudio plan = planEstudioRepository.findById(planId)
                .orElseThrow(() -> new ResourceNotFoundException("Plan de estudio no encontrado: " + planId));

        if (planMateriaRepository.existsByPlanIdAndMateriaId(planId, materiaId)) {
            throw new ConflictException("MATERIA_YA_ASOCIADA_AL_PLAN", "Esa materia ya está asociada a este plan");
        }
        Integer cuatrimestre = validarCuatrimestre(materia, request.cuatrimestre());

        PlanMateria planMateria = PlanMateria.builder()
                .plan(plan).materia(materia).anio(request.anio()).cuatrimestre(cuatrimestre)
                .build();
        planMateriaRepository.save(planMateria);

        auditoriaPublisher.publicar(SecurityUtils.usuarioActualId(), "MATERIA_ASOCIADA_A_PLAN", "PlanMateria", planId,
                detalleAsociacion(materia, request.anio(), cuatrimestre));
    }

    @Override
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    @Transactional
    public void actualizarAsociacion(Long materiaId, Long planId, PlanMateriaRequest request) {
        PlanMateria asociacion = buscarAsociacionOFallar(materiaId, planId);
        Integer cuatrimestre = validarCuatrimestre(asociacion.getMateria(), request.cuatrimestre());
        asociacion.setAnio(request.anio());
        asociacion.setCuatrimestre(cuatrimestre);
        planMateriaRepository.save(asociacion);
        auditoriaPublisher.publicar(SecurityUtils.usuarioActualId(), "ASOCIACION_PLAN_MATERIA_ACTUALIZADA", "PlanMateria",
                planId, detalleAsociacion(asociacion.getMateria(), request.anio(), cuatrimestre));
    }

    @Override
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    @Transactional
    public void desasociarDePlan(Long materiaId, Long planId) {
        PlanMateria asociacion = buscarAsociacionOFallar(materiaId, planId);
        Long carreraId = asociacion.getPlan().getCarrera().getId();
        // Las comisiones exigen que la materia pertenezca a su carrera: si este es el unico plan de la carrera que la
        // tiene y hay comisiones, quitarla dejaria esas comisiones inconsistentes.
        if (planMateriaRepository.contarPlanesDeLaCarreraConMateria(materiaId, carreraId) <= 1
                && comisionRepository.existsByMateriaIdAndCarreraId(materiaId, carreraId)) {
            throw new BusinessException("MATERIA_CON_COMISIONES", "No se puede quitar \"" + asociacion.getMateria().getNombre()
                    + "\" del plan: tiene comisiones en esta carrera. Eliminá o reasigná esas comisiones primero");
        }
        planMateriaRepository.delete(asociacion);
        auditoriaPublisher.publicar(SecurityUtils.usuarioActualId(), "MATERIA_DESASOCIADA_DE_PLAN", "PlanMateria", planId,
                Map.of("materiaId", materiaId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<PlanMateriaResponse> listarDelPlan(Long planId) {
        if (!planEstudioRepository.existsById(planId)) {
            throw new ResourceNotFoundException("Plan de estudio no encontrado: " + planId);
        }
        // Orden de la malla: año, luego anuales, 1° y 2° cuatrimestre, y nombre.
        return planMateriaRepository.findByPlanId(planId).stream()
                .sorted(Comparator.comparingInt(PlanMateria::getAnio)
                        .thenComparing(pm -> pm.getCuatrimestre() == null ? 0 : pm.getCuatrimestre())
                        .thenComparing(pm -> pm.getMateria().getNombre(), String.CASE_INSENSITIVE_ORDER))
                .map(pm -> new PlanMateriaResponse(planId, pm.getMateria().getId(), pm.getMateria().getCodigo(),
                        pm.getMateria().getNombre(), pm.getMateria().getCargaHoraria(), pm.getMateria().isActiva(),
                        pm.getMateria().getModalidad().name(), pm.getAnio(), pm.getCuatrimestre()))
                .toList();
    }

    private static ModalidadCursada modalidadDe(MateriaRequest request) {
        return request.modalidad() == null ? ModalidadCursada.CUATRIMESTRAL : request.modalidad();
    }

    /**
     * Cuatrimestral -> anual: sus ubicaciones en los planes pierden el cuatrimestre (pasan a "todo el año").
     * Anual -> cuatrimestral: si ya esta en algun plan no se puede adivinar en que cuatrimestre va; hay que quitarla
     * del plan y volver a asociarla (o asociarla primero con la modalidad nueva).
     */
    private void cambiarModalidad(Materia materia, ModalidadCursada nueva) {
        ModalidadCursada actual = materia.getModalidad() == null ? ModalidadCursada.CUATRIMESTRAL : materia.getModalidad();
        if (actual == nueva) {
            return;
        }
        List<PlanMateria> asociaciones = planMateriaRepository.findByMateriaId(materia.getId());
        if (nueva == ModalidadCursada.CUATRIMESTRAL && !asociaciones.isEmpty()) {
            throw new BusinessException("MODALIDAD_CON_ASOCIACIONES", "La materia ya está en " + asociaciones.size()
                    + " plan(es) como anual: para hacerla cuatrimestral quitala de esos planes y asociala de nuevo "
                    + "indicando el cuatrimestre");
        }
        asociaciones.forEach(pm -> pm.setCuatrimestre(null));
        planMateriaRepository.saveAll(asociaciones);
        materia.setModalidad(nueva);
    }

    /** Cuatrimestral: 1 o 2 obligatorio. Anual: sin cuatrimestre. */
    private Integer validarCuatrimestre(Materia materia, Integer cuatrimestre) {
        if (materia.getModalidad() == ModalidadCursada.ANUAL) {
            if (cuatrimestre != null) {
                throw new BusinessException("CUATRIMESTRE_NO_CORRESPONDE", "\"" + materia.getNombre()
                        + "\" es anual: se asocia al plan sin cuatrimestre");
            }
            return null;
        }
        if (cuatrimestre == null || cuatrimestre < 1 || cuatrimestre > 2) {
            throw new BusinessException("CUATRIMESTRE_REQUERIDO", "\"" + materia.getNombre()
                    + "\" es cuatrimestral: indicá el cuatrimestre (1 o 2)");
        }
        return cuatrimestre;
    }

    private Map<String, Object> detalleAsociacion(Materia materia, int anio, Integer cuatrimestre) {
        Map<String, Object> detalle = new HashMap<>();
        detalle.put("materiaId", materia.getId());
        detalle.put("anio", anio);
        detalle.put("cuatrimestre", cuatrimestre); // null para las anuales (Map.of no admite null)
        detalle.put("modalidad", materia.getModalidad().name());
        return detalle;
    }

    private PlanMateria buscarAsociacionOFallar(Long materiaId, Long planId) {
        return planMateriaRepository.findByPlanIdAndMateriaId(planId, materiaId)
                .orElseThrow(() -> new ResourceNotFoundException("La materia " + materiaId + " no está en el plan " + planId));
    }

    private Materia buscarOFallar(Long id) {
        return materiaRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Materia no encontrada: " + id));
    }
}
