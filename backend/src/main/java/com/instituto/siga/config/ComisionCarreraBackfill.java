package com.instituto.siga.config;

import com.instituto.siga.entity.Comision;
import com.instituto.siga.repository.CarreraRepository;
import com.instituto.siga.repository.ComisionRepository;
import com.instituto.siga.repository.PlanMateriaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Al arrancar, completa la carrera de las comisiones creadas antes de que la comisión tuviera carrera (la base
 * local persiste, así que esas filas siguen ahí). Solo se completa cuando no hay dudas: la materia pertenece a UNA
 * carrera y no choca con otra comisión del mismo nombre y período. Las demás quedan sin carrera y se avisan en el
 * log, para que el administrador las edite a mano: nada se borra solo.
 */
@Slf4j
@Component
@Order(Ordered.LOWEST_PRECEDENCE) // data.sql ya siembra las comisiones con carrera: el orden respecto del seed no importa
@RequiredArgsConstructor
public class ComisionCarreraBackfill implements ApplicationRunner {

    private final ComisionRepository comisionRepository;
    private final PlanMateriaRepository planMateriaRepository;
    private final CarreraRepository carreraRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        for (Comision comision : comisionRepository.findByCarreraIsNull()) {
            Long materiaId = comision.getMateria().getId();
            List<Long> carreras = planMateriaRepository.buscarIdsCarreraPorMateria(materiaId);
            if (carreras.size() != 1) {
                log.warn("Comisión {} (\"{}\" de {}): no se pudo asignar carrera automáticamente ({}). Editala desde "
                                + "Administración → Comisiones.", comision.getId(), comision.getNombre(),
                        comision.getMateria().getNombre(),
                        carreras.isEmpty() ? "la materia no está en ningún plan" : "la materia está en varias carreras");
                continue;
            }
            Long carreraId = carreras.get(0);
            if (comisionRepository.existsByMateriaIdAndCarreraIdAndNombreAndPeriodoLectivo(
                    materiaId, carreraId, comision.getNombre(), comision.getPeriodoLectivo())) {
                log.warn("Comisión {} (\"{}\" de {}): ya existe otra con el mismo nombre y período en su carrera. "
                                + "Editala desde Administración → Comisiones.",
                        comision.getId(), comision.getNombre(), comision.getMateria().getNombre());
                continue;
            }
            carreraRepository.findById(carreraId).ifPresent(carrera -> {
                comision.setCarrera(carrera);
                comisionRepository.saveAndFlush(comision);
                log.info("Comisión {} asignada a la carrera {}", comision.getId(), carrera.getNombre());
            });
        }
    }
}
