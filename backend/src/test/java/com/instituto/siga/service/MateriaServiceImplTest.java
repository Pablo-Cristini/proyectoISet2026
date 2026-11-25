package com.instituto.siga.service;

import com.instituto.siga.audit.AuditoriaPublisher;
import com.instituto.siga.dto.request.MateriaRequest;
import com.instituto.siga.dto.request.PlanMateriaRequest;
import com.instituto.siga.dto.response.MateriaResponse;
import com.instituto.siga.entity.Materia;
import com.instituto.siga.entity.PlanEstudio;
import com.instituto.siga.exception.ConflictException;
import com.instituto.siga.exception.ResourceNotFoundException;
import com.instituto.siga.mapper.MateriaMapper;
import com.instituto.siga.repository.MateriaRepository;
import com.instituto.siga.repository.PlanEstudioRepository;
import com.instituto.siga.repository.PlanMateriaRepository;
import com.instituto.siga.service.impl.MateriaServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MateriaServiceImplTest {

    @Mock
    private MateriaRepository materiaRepository;
    @Mock
    private PlanEstudioRepository planEstudioRepository;
    @Mock
    private PlanMateriaRepository planMateriaRepository;
    @Mock
    private MateriaMapper materiaMapper;
    @Mock
    private AuditoriaPublisher auditoriaPublisher;
    @Mock
    private com.instituto.siga.repository.ComisionRepository comisionRepository;

    private MateriaServiceImpl materiaService;

    @BeforeEach
    void setUp() {
        materiaService = new MateriaServiceImpl(materiaRepository, planEstudioRepository, planMateriaRepository,
                materiaMapper, auditoriaPublisher, comisionRepository);
    }

    @Test
    void crear_conCodigoDuplicado_lanzaConflictException() {
        MateriaRequest request = new MateriaRequest("MAT101", "Programación I", 120, null);
        when(materiaRepository.existsByCodigo("MAT101")).thenReturn(true);

        assertThatThrownBy(() -> materiaService.crear(request))
                .isInstanceOf(ConflictException.class)
                .extracting("codigo").isEqualTo("MATERIA_DUPLICADA");
    }

    @Test
    void crear_conDatosValidos_persisteActiva() {
        MateriaRequest request = new MateriaRequest("MAT101", "Programación I", 120, null);
        Materia mapeada = Materia.builder().codigo(request.codigo()).nombre(request.nombre())
                .cargaHoraria(request.cargaHoraria()).build();

        when(materiaRepository.existsByCodigo(any())).thenReturn(false);
        when(materiaMapper.toEntity(request)).thenReturn(mapeada);
        when(materiaRepository.save(any(Materia.class))).thenAnswer(inv -> inv.getArgument(0));
        when(materiaMapper.toResponse(any(Materia.class)))
                .thenReturn(new MateriaResponse(1L, "MAT101", "Programación I", 120, true, "CUATRIMESTRAL"));

        materiaService.crear(request);

        ArgumentCaptor<Materia> captor = ArgumentCaptor.forClass(Materia.class);
        verify(materiaRepository).save(captor.capture());
        assertThat(captor.getValue().isActiva()).isTrue();
    }

    @Test
    void obtenerPorId_conIdInexistente_lanzaResourceNotFoundException() {
        when(materiaRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> materiaService.obtenerPorId(999L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void darDeBaja_marcaActivaComoFalse() {
        Materia materia = Materia.builder().id(1L).codigo("MAT101").nombre("N").cargaHoraria(100).activa(true).build();
        when(materiaRepository.findById(1L)).thenReturn(Optional.of(materia));
        when(materiaRepository.save(any(Materia.class))).thenAnswer(inv -> inv.getArgument(0));

        materiaService.darDeBaja(1L);

        assertThat(materia.isActiva()).isFalse();
    }

    @Test
    void asociarAPlan_conPlanInexistente_lanzaResourceNotFoundException() {
        Materia materia = Materia.builder().id(1L).codigo("MAT101").nombre("N").cargaHoraria(100).activa(true).build();
        when(materiaRepository.findById(1L)).thenReturn(Optional.of(materia));
        when(planEstudioRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> materiaService.asociarAPlan(1L, 99L, new PlanMateriaRequest(1, 1)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void asociarAPlan_yaAsociada_lanzaConflictException() {
        Materia materia = Materia.builder().id(1L).codigo("MAT101").nombre("N").cargaHoraria(100).activa(true).build();
        PlanEstudio plan = PlanEstudio.builder().id(2L).version("2024").vigente(true).build();
        when(materiaRepository.findById(1L)).thenReturn(Optional.of(materia));
        when(planEstudioRepository.findById(2L)).thenReturn(Optional.of(plan));
        when(planMateriaRepository.existsByPlanIdAndMateriaId(2L, 1L)).thenReturn(true);

        assertThatThrownBy(() -> materiaService.asociarAPlan(1L, 2L, new PlanMateriaRequest(1, 1)))
                .isInstanceOf(ConflictException.class)
                .extracting("codigo").isEqualTo("MATERIA_YA_ASOCIADA_AL_PLAN");
    }

    @Test
    void asociarAPlan_conDatosValidos_persiste() {
        Materia materia = Materia.builder().id(1L).codigo("MAT101").nombre("N").cargaHoraria(100).activa(true).build();
        PlanEstudio plan = PlanEstudio.builder().id(2L).version("2024").vigente(true).build();
        when(materiaRepository.findById(1L)).thenReturn(Optional.of(materia));
        when(planEstudioRepository.findById(2L)).thenReturn(Optional.of(plan));
        when(planMateriaRepository.existsByPlanIdAndMateriaId(2L, 1L)).thenReturn(false);

        materiaService.asociarAPlan(1L, 2L, new PlanMateriaRequest(1, 2));

        verify(planMateriaRepository).save(any());
    }

    // ------------------------------------------------ materias anuales y cuatrimestrales

    private Materia materia(com.instituto.siga.entity.ModalidadCursada modalidad) {
        return Materia.builder().id(1L).codigo("MAT101").nombre("Matemática").cargaHoraria(100).activa(true)
                .modalidad(modalidad).build();
    }

    private void planExistente() {
        when(planEstudioRepository.findById(2L)).thenReturn(Optional.of(PlanEstudio.builder().id(2L).version("2024").vigente(true).build()));
        when(planMateriaRepository.existsByPlanIdAndMateriaId(2L, 1L)).thenReturn(false);
    }

    @Test
    void crear_materiaAnual_persisteLaModalidad_ySinModalidadEsCuatrimestral() {
        when(materiaRepository.existsByCodigo(any())).thenReturn(false);
        when(materiaMapper.toEntity(any())).thenAnswer(inv -> Materia.builder().codigo("X").nombre("X").cargaHoraria(1).build());
        when(materiaRepository.save(any(Materia.class))).thenAnswer(inv -> inv.getArgument(0));
        ArgumentCaptor<Materia> captor = ArgumentCaptor.forClass(Materia.class);

        materiaService.crear(new MateriaRequest("ANU1", "Anual", 96, com.instituto.siga.entity.ModalidadCursada.ANUAL));
        materiaService.crear(new MateriaRequest("CUA1", "Cuatri", 64, null));

        verify(materiaRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues().get(0).getModalidad()).isEqualTo(com.instituto.siga.entity.ModalidadCursada.ANUAL);
        assertThat(captor.getAllValues().get(1).getModalidad()).isEqualTo(com.instituto.siga.entity.ModalidadCursada.CUATRIMESTRAL);
    }

    @Test
    void asociarAPlan_materiaAnual_seGuardaSinCuatrimestre() {
        when(materiaRepository.findById(1L)).thenReturn(Optional.of(materia(com.instituto.siga.entity.ModalidadCursada.ANUAL)));
        planExistente();

        materiaService.asociarAPlan(1L, 2L, new PlanMateriaRequest(1, null));

        ArgumentCaptor<com.instituto.siga.entity.PlanMateria> captor = ArgumentCaptor.forClass(com.instituto.siga.entity.PlanMateria.class);
        verify(planMateriaRepository).save(captor.capture());
        assertThat(captor.getValue().getCuatrimestre()).isNull();
        assertThat(captor.getValue().getAnio()).isEqualTo(1);
    }

    @Test
    void asociarAPlan_materiaAnualConCuatrimestre_lanzaBusinessException() {
        when(materiaRepository.findById(1L)).thenReturn(Optional.of(materia(com.instituto.siga.entity.ModalidadCursada.ANUAL)));
        planExistente();

        assertThatThrownBy(() -> materiaService.asociarAPlan(1L, 2L, new PlanMateriaRequest(1, 2)))
                .isInstanceOf(com.instituto.siga.exception.BusinessException.class)
                .extracting("codigo").isEqualTo("CUATRIMESTRE_NO_CORRESPONDE");
    }

    @Test
    void asociarAPlan_materiaCuatrimestralSinCuatrimestre_lanzaBusinessException() {
        when(materiaRepository.findById(1L)).thenReturn(Optional.of(materia(com.instituto.siga.entity.ModalidadCursada.CUATRIMESTRAL)));
        planExistente();

        assertThatThrownBy(() -> materiaService.asociarAPlan(1L, 2L, new PlanMateriaRequest(1, null)))
                .isInstanceOf(com.instituto.siga.exception.BusinessException.class)
                .extracting("codigo").isEqualTo("CUATRIMESTRE_REQUERIDO");
    }

    @Test
    void actualizar_deCuatrimestralAAnual_lesQuitaElCuatrimestreEnLosPlanes() {
        Materia materia = materia(com.instituto.siga.entity.ModalidadCursada.CUATRIMESTRAL);
        com.instituto.siga.entity.PlanMateria pm = com.instituto.siga.entity.PlanMateria.builder().materia(materia).anio(2).cuatrimestre(1).build();
        when(materiaRepository.findById(1L)).thenReturn(Optional.of(materia));
        when(materiaRepository.existsByCodigoAndIdNot("MAT101", 1L)).thenReturn(false);
        when(planMateriaRepository.findByMateriaId(1L)).thenReturn(java.util.List.of(pm));
        when(materiaRepository.save(any(Materia.class))).thenAnswer(inv -> inv.getArgument(0));

        materiaService.actualizar(1L, new MateriaRequest("MAT101", "Matemática", 100, com.instituto.siga.entity.ModalidadCursada.ANUAL));

        assertThat(materia.getModalidad()).isEqualTo(com.instituto.siga.entity.ModalidadCursada.ANUAL);
        assertThat(pm.getCuatrimestre()).isNull();
        assertThat(pm.getAnio()).isEqualTo(2);
    }

    @Test
    void actualizar_deAnualACuatrimestralConPlanes_lanzaBusinessException() {
        Materia materia = materia(com.instituto.siga.entity.ModalidadCursada.ANUAL);
        when(materiaRepository.findById(1L)).thenReturn(Optional.of(materia));
        when(materiaRepository.existsByCodigoAndIdNot("MAT101", 1L)).thenReturn(false);
        when(planMateriaRepository.findByMateriaId(1L)).thenReturn(java.util.List.of(
                com.instituto.siga.entity.PlanMateria.builder().materia(materia).anio(1).build()));

        assertThatThrownBy(() -> materiaService.actualizar(1L,
                new MateriaRequest("MAT101", "Matemática", 100, com.instituto.siga.entity.ModalidadCursada.CUATRIMESTRAL)))
                .isInstanceOf(com.instituto.siga.exception.BusinessException.class)
                .extracting("codigo").isEqualTo("MODALIDAD_CON_ASOCIACIONES");
        assertThat(materia.getModalidad()).isEqualTo(com.instituto.siga.entity.ModalidadCursada.ANUAL);
    }

    @Test
    void desasociarDePlan_conComisionesEnLaCarrera_lanzaBusinessException_yNoBorra() {
        com.instituto.siga.entity.Carrera carrera = com.instituto.siga.entity.Carrera.builder().id(3L).build();
        PlanEstudio plan = PlanEstudio.builder().id(2L).carrera(carrera).version("2024").build();
        com.instituto.siga.entity.PlanMateria pm = com.instituto.siga.entity.PlanMateria.builder()
                .plan(plan).materia(materia(com.instituto.siga.entity.ModalidadCursada.ANUAL)).anio(1).build();
        when(planMateriaRepository.findByPlanIdAndMateriaId(2L, 1L)).thenReturn(Optional.of(pm));
        when(planMateriaRepository.contarPlanesDeLaCarreraConMateria(1L, 3L)).thenReturn(1L);
        when(comisionRepository.existsByMateriaIdAndCarreraId(1L, 3L)).thenReturn(true);

        assertThatThrownBy(() -> materiaService.desasociarDePlan(1L, 2L))
                .isInstanceOf(com.instituto.siga.exception.BusinessException.class)
                .extracting("codigo").isEqualTo("MATERIA_CON_COMISIONES");
        verify(planMateriaRepository, org.mockito.Mockito.never()).delete(any());
    }
}
