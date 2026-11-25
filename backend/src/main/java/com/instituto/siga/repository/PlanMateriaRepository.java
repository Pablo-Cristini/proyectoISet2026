package com.instituto.siga.repository;

import com.instituto.siga.entity.PlanMateria;
import com.instituto.siga.entity.PlanMateriaId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PlanMateriaRepository extends JpaRepository<PlanMateria, PlanMateriaId> {

    List<PlanMateria> findByPlanId(Long planId);

    List<PlanMateria> findByMateriaId(Long materiaId);

    boolean existsByPlanIdAndMateriaId(Long planId, Long materiaId);

    Optional<PlanMateria> findByPlanIdAndMateriaId(Long planId, Long materiaId);

    /** En cuantos planes de la carrera esta la materia. */
    @Query("SELECT COUNT(pm) FROM PlanMateria pm WHERE pm.materia.id = :materiaId AND pm.plan.carrera.id = :carreraId")
    long contarPlanesDeLaCarreraConMateria(@Param("materiaId") Long materiaId, @Param("carreraId") Long carreraId);

    /** El alumno no esta vinculado a una carrera en JPA: se resuelve por Materia -> Plan -> Carrera,
     * prefiriendo el plan vigente si la materia esta asociada a mas de uno. */
    @Query("SELECT c.nombre FROM PlanMateria pm JOIN pm.plan p JOIN p.carrera c "
            + "WHERE pm.materia.id = :materiaId ORDER BY p.vigente DESC")
    List<String> buscarNombresCarreraPorMateria(@Param("materiaId") Long materiaId);

    /** Codigos de carrera (plan vigente primero): eligen la hoja del Excel del historial (SIST -> SIST_R6790_19). */
    @Query("SELECT c.codigo FROM PlanMateria pm JOIN pm.plan p JOIN p.carrera c "
            + "WHERE pm.materia.id = :materiaId ORDER BY p.vigente DESC")
    List<String> buscarCodigosCarreraPorMateria(@Param("materiaId") Long materiaId);

    /** Carreras a las que pertenece una materia (a traves de sus planes de estudio). */
    @Query("SELECT DISTINCT p.carrera.id FROM PlanMateria pm JOIN pm.plan p WHERE pm.materia.id = :materiaId")
    List<Long> buscarIdsCarreraPorMateria(@Param("materiaId") Long materiaId);

    default Optional<String> buscarCarreraVigentePorMateria(Long materiaId) {
        List<String> nombres = buscarNombresCarreraPorMateria(materiaId);
        return nombres.isEmpty() ? Optional.empty() : Optional.of(nombres.get(0));
    }
}
