package com.instituto.siga.repository;

import com.instituto.siga.entity.Comision;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ComisionRepository extends JpaRepository<Comision, Long> {

    List<Comision> findByMateriaId(Long materiaId);

    List<Comision> findByPeriodoLectivo(String periodoLectivo);

    List<Comision> findByMateriaIdAndPeriodoLectivo(Long materiaId, String periodoLectivo);

    /** Hay al menos una comisión de la materia en la carrera (protege quitar la materia del plan). */
    boolean existsByMateriaIdAndCarreraId(Long materiaId, Long carreraId);

    /** No se repite el mismo nombre en el mismo período para la misma materia y carrera. */
    boolean existsByMateriaIdAndCarreraIdAndNombreAndPeriodoLectivo(Long materiaId, Long carreraId,
                                                                    String nombre, String periodoLectivo);

    boolean existsByMateriaIdAndCarreraIdAndNombreAndPeriodoLectivoAndIdNot(Long materiaId, Long carreraId,
                                                                            String nombre, String periodoLectivo, Long id);

    /** Comisiones creadas antes de que existiera la regla por carrera (las completa ComisionCarreraBackfill). */
    List<Comision> findByCarreraIsNull();
}
