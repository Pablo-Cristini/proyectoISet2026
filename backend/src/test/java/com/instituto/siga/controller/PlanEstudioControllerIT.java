package com.instituto.siga.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instituto.siga.dto.request.CarreraRequest;
import com.instituto.siga.dto.request.LoginRequest;
import com.instituto.siga.dto.request.MateriaRequest;
import com.instituto.siga.dto.request.PlanEstudioRequest;
import com.instituto.siga.dto.request.PlanMateriaRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Cubre Plan de Estudio (alta/actualizacion/listado) y asociacion Materia-Plan con JWT reales (docs/07 SS4.2). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class PlanEstudioControllerIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    private String tokenPara(String dni, String password) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(dni, password))))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("accessToken").asText();
    }

    private Long crearCarrera(String admin, String codigo, String nombre) throws Exception {
        String body = mockMvc.perform(post("/api/v1/carreras")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CarreraRequest(codigo, nombre))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    private Long crearMateria(String admin, String codigo, String nombre) throws Exception {
        String body = mockMvc.perform(post("/api/v1/materias")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new MateriaRequest(codigo, nombre, 100, null))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    private Long crearPlan(String admin, Long carreraId, String version) throws Exception {
        String body = mockMvc.perform(post("/api/v1/carreras/" + carreraId + "/planes")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PlanEstudioRequest(version, true))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    @Test
    void alumno_noPuedeCrearPlan_devuelve403() throws Exception {
        String admin = tokenPara("30111222", "Admin1234");
        String alumno = tokenPara("30333444", "Alumno1234");
        Long carreraId = crearCarrera(admin, "PLAN-IT1", "Tec. en Sistemas IT1");

        mockMvc.perform(post("/api/v1/carreras/" + carreraId + "/planes")
                        .header("Authorization", "Bearer " + alumno)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PlanEstudioRequest("2024", true))))
                .andExpect(status().isForbidden());
    }

    @Test
    void admin_creaPlan_yLaVersionDuplicadaParaLaMismaCarreraDevuelve409() throws Exception {
        String admin = tokenPara("30111222", "Admin1234");
        Long carreraId = crearCarrera(admin, "PLAN-IT2", "Tec. en Sistemas IT2");

        mockMvc.perform(post("/api/v1/carreras/" + carreraId + "/planes")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PlanEstudioRequest("2024", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("2024"))
                .andExpect(jsonPath("$.vigente").value(true));

        mockMvc.perform(post("/api/v1/carreras/" + carreraId + "/planes")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PlanEstudioRequest("2024", true))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("PLAN_ESTUDIO_DUPLICADO"));
    }

    @Test
    void cualquierAutenticado_puedeListarPlanesDeUnaCarrera() throws Exception {
        String admin = tokenPara("30111222", "Admin1234");
        String alumno = tokenPara("30333444", "Alumno1234");
        Long carreraId = crearCarrera(admin, "PLAN-IT3", "Tec. en Sistemas IT3");
        crearPlan(admin, carreraId, "2024");

        mockMvc.perform(get("/api/v1/carreras/" + carreraId + "/planes")
                        .header("Authorization", "Bearer " + alumno))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].version").value("2024"));
    }

    @Test
    void admin_actualizaPlan_yConVersionDuplicadaDeOtroPlanDevuelve409() throws Exception {
        String admin = tokenPara("30111222", "Admin1234");
        Long carreraId = crearCarrera(admin, "PLAN-IT4", "Tec. en Sistemas IT4");
        Long plan2023 = crearPlan(admin, carreraId, "2023");
        Long plan2024 = crearPlan(admin, carreraId, "2024");

        mockMvc.perform(put("/api/v1/planes-estudio/" + plan2023)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PlanEstudioRequest("2023-B", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("2023-B"))
                .andExpect(jsonPath("$.vigente").value(false));

        mockMvc.perform(put("/api/v1/planes-estudio/" + plan2024)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PlanEstudioRequest("2023-B", true))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("PLAN_ESTUDIO_DUPLICADO"));
    }

    @Test
    void admin_asociaMateriaAPlan_yLaAsociacionDuplicadaDevuelve409() throws Exception {
        String admin = tokenPara("30111222", "Admin1234");
        Long carreraId = crearCarrera(admin, "PLAN-IT5", "Tec. en Sistemas IT5");
        Long planId = crearPlan(admin, carreraId, "2024");
        Long materiaId = crearMateria(admin, "MAT-PLAN1", "Materia Plan 1");

        mockMvc.perform(post("/api/v1/materias/" + materiaId + "/plan/" + planId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PlanMateriaRequest(1, 1))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/materias/" + materiaId + "/plan/" + planId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PlanMateriaRequest(1, 1))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("MATERIA_YA_ASOCIADA_AL_PLAN"));
    }

    @Test
    void listarMaterias_filtraPorCarreraPlanYAnio() throws Exception {
        String admin = tokenPara("30111222", "Admin1234");
        Long carreraId = crearCarrera(admin, "PLAN-IT6", "Tec. en Sistemas IT6");
        Long planId = crearPlan(admin, carreraId, "2024");
        Long materiaAsociada = crearMateria(admin, "MAT-PLAN2", "Materia Plan 2");
        crearMateria(admin, "MAT-PLAN3", "Materia Sin Plan");

        mockMvc.perform(post("/api/v1/materias/" + materiaAsociada + "/plan/" + planId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PlanMateriaRequest(1, 1))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/materias")
                        .header("Authorization", "Bearer " + admin)
                        .param("carreraId", String.valueOf(carreraId))
                        .param("planId", String.valueOf(planId))
                        .param("anio", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].codigo").value("MAT-PLAN2"));
    }

    // ------------------------------------------------ version de hasta 100 caracteres

    @Test
    void version_de100Caracteres_seGuardaYSeRecuperaCompleta_101Devuelve400() throws Exception {
        String admin = tokenPara("30111222", "Admin1234");
        Long carreraId = crearCarrera(admin, "PLAN-V100", "Carrera version larga");
        String cien = "Plan 2026 - Resolucion Ministerial 1234/2026 - Texto ordenado con modificaciones - Anexo I y II ok!!";
        String cien2 = cien.substring(0, 99) + "X";
        org.assertj.core.api.Assertions.assertThat(cien).hasSize(100);

        Long planId = crearPlan(admin, carreraId, cien);
        mockMvc.perform(get("/api/v1/carreras/" + carreraId + "/planes").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].version").value(cien));
        // modificar a otra version de 100 caracteres
        mockMvc.perform(put("/api/v1/planes-estudio/" + planId).header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PlanEstudioRequest(cien2, true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(cien2));
        // 101 caracteres: rechazado por la validacion, no truncado
        mockMvc.perform(post("/api/v1/carreras/" + carreraId + "/planes").header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PlanEstudioRequest(cien + "Z", true))))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------ materias anuales en el plan

    private Long crearMateriaConModalidad(String admin, String codigo, String modalidad) throws Exception {
        String body = mockMvc.perform(post("/api/v1/materias").header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"codigo\":\"" + codigo + "\",\"nombre\":\"Materia " + codigo + "\",\"cargaHoraria\":96"
                                + (modalidad == null ? "" : ",\"modalidad\":\"" + modalidad + "\"") + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modalidad").value(modalidad == null ? "CUATRIMESTRAL" : modalidad))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    @Test
    void materiasAnualesYCuatrimestrales_enElPlan_crearAsociarVerEditarYQuitar() throws Exception {
        String admin = tokenPara("30111222", "Admin1234");
        Long carreraId = crearCarrera(admin, "PLAN-ANU", "Carrera con anuales");
        Long planId = crearPlan(admin, carreraId, "2026");
        Long anual = crearMateriaConModalidad(admin, "ANU-IT1", "ANUAL");
        Long cuatri = crearMateriaConModalidad(admin, "CUA-IT1", null); // sin modalidad: cuatrimestral

        // asociar: la anual sin cuatrimestre, la cuatrimestral con cuatrimestre
        mockMvc.perform(post("/api/v1/materias/" + anual + "/plan/" + planId).header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"anio\":1,\"cuatrimestre\":null}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/materias/" + cuatri + "/plan/" + planId).header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"anio\":1,\"cuatrimestre\":2}"))
                .andExpect(status().isOk());
        // validaciones por modalidad
        Long otraAnual = crearMateriaConModalidad(admin, "ANU-IT2", "ANUAL");
        mockMvc.perform(post("/api/v1/materias/" + otraAnual + "/plan/" + planId).header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"anio\":1,\"cuatrimestre\":1}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.codigo").value("CUATRIMESTRE_NO_CORRESPONDE"));
        Long otraCuatri = crearMateriaConModalidad(admin, "CUA-IT2", "CUATRIMESTRAL");
        mockMvc.perform(post("/api/v1/materias/" + otraCuatri + "/plan/" + planId).header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"anio\":1}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.codigo").value("CUATRIMESTRE_REQUERIDO"));

        // visualizacion de la malla: anual sin cuatrimestre, cuatrimestral con el suyo
        mockMvc.perform(get("/api/v1/planes-estudio/" + planId + "/materias").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].materiaId").value(anual))
                .andExpect(jsonPath("$[0].modalidad").value("ANUAL"))
                .andExpect(jsonPath("$[0].cuatrimestre").doesNotExist())
                .andExpect(jsonPath("$[1].materiaId").value(cuatri))
                .andExpect(jsonPath("$[1].cuatrimestre").value(2));
        // y el listado de materias por plan (lo usan otras pantallas) las sigue devolviendo a las dos
        mockMvc.perform(get("/api/v1/materias").param("planId", planId.toString()).header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        // editar la asociacion de la anual (cambiar de año) y de la cuatrimestral (cambiar de cuatrimestre)
        mockMvc.perform(put("/api/v1/materias/" + anual + "/plan/" + planId).header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"anio\":2,\"cuatrimestre\":null}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/v1/materias/" + cuatri + "/plan/" + planId).header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"anio\":1,\"cuatrimestre\":1}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/planes-estudio/" + planId + "/materias").header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$[0].materiaId").value(cuatri))
                .andExpect(jsonPath("$[0].cuatrimestre").value(1))
                .andExpect(jsonPath("$[1].materiaId").value(anual))
                .andExpect(jsonPath("$[1].anio").value(2));

        // modificar la materia anual (nombre, carga) sin perder su modalidad
        mockMvc.perform(put("/api/v1/materias/" + anual).header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"codigo\":\"ANU-IT1\",\"nombre\":\"Anual renombrada\",\"cargaHoraria\":128,\"modalidad\":\"ANUAL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modalidad").value("ANUAL"))
                .andExpect(jsonPath("$.nombre").value("Anual renombrada"));
        // pasarla a cuatrimestral estando en un plan: no se adivina el cuatrimestre
        mockMvc.perform(put("/api/v1/materias/" + anual).header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"codigo\":\"ANU-IT1\",\"nombre\":\"Anual renombrada\",\"cargaHoraria\":128,\"modalidad\":\"CUATRIMESTRAL\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.codigo").value("MODALIDAD_CON_ASOCIACIONES"));

        // quitar asociaciones
        mockMvc.perform(delete("/api/v1/materias/" + anual + "/plan/" + planId).header("Authorization", "Bearer " + admin))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/planes-estudio/" + planId + "/materias").header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].materiaId").value(cuatri));

        // la anual se puede usar en comisiones igual que una cuatrimestral
        mockMvc.perform(post("/api/v1/materias/" + anual + "/plan/" + planId).header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"anio\":1}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/comisiones").header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"materiaId\":" + anual + ",\"carreraId\":" + carreraId
                                + ",\"nombre\":\"A\",\"periodoLectivo\":\"2026\",\"cupoMaximo\":30,\"horarios\":[]}"))
                .andExpect(status().isOk());
        // con comisiones, quitarla del unico plan de la carrera se bloquea (las dejaria inconsistentes)
        mockMvc.perform(delete("/api/v1/materias/" + anual + "/plan/" + planId).header("Authorization", "Bearer " + admin))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.codigo").value("MATERIA_CON_COMISIONES"));
    }
}
