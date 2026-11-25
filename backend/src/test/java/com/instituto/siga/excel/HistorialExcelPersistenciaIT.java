package com.instituto.siga.excel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.instituto.siga.entity.EstadoAcademicoMateria;
import com.instituto.siga.entity.EstadoMateria;
import com.instituto.siga.repository.EstadoAcademicoMateriaRepository;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PRUEBAS 1, 2, 3 y 5 de persistencia del historial Excel, de punta a punta (API real con JWT -> servicio ->
 * base H2 -> data/historial-academico.xlsx de prueba). Cada paso verifica la BASE (API de historial) y el
 * ARCHIVO (releido con el mismo lector del sistema). Ademas cubre la falla que originaba la perdida de datos:
 * un cambio de la base que no llego al Excel (archivo bloqueado) ya no es pisado por el valor viejo del archivo.
 * La prueba 4 (reinicio real del backend) esta en HistorialExcelReinicioIT.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class HistorialExcelPersistenciaIT {

    private static final Path ARCHIVO;

    static {
        try {
            Path dir = Files.createTempDirectory("siga-persistencia-it");
            ARCHIVO = dir.resolve("historial-academico.xlsx");
            try (InputStream plantilla = new ClassPathResource(HistorialExcelBootstrap.PLANTILLA).getInputStream()) {
                Files.copy(plantilla, ARCHIVO, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registry) {
        registry.add("app.historial.excel-path", () -> ARCHIVO.toAbsolutePath().toString());
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:sigadb_persistencia;MODE=MySQL;DB_CLOSE_DELAY=-1");
    }

    private static final String DNI = "99000555";
    private static final String MATERIA = "FISICA"; // columna presente en una sola hoja de la plantilla

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private HistorialExcelReader reader;
    @Autowired
    private EstadoAcademicoMateriaRepository estadoRepository;

    private JsonNode json(String body) throws Exception {
        return objectMapper.readTree(body);
    }

    private String tokenAdmin() throws Exception {
        return json(mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dni\":\"30111222\",\"password\":\"Admin1234\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("accessToken").asText();
    }

    private JsonNode fijar(String admin, long alumnoId, long materiaId, String cuerpo) throws Exception {
        return json(mockMvc.perform(put("/api/v1/alumnos/" + alumnoId + "/estado-academico/" + materiaId)
                        .header("Authorization", "Bearer " + admin).contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    /** Estado de la materia en la BASE, segun la API de historial (null si no figura). */
    private JsonNode enLaBase(String admin, long alumnoId) throws Exception {
        JsonNode lista = json(mockMvc.perform(get("/api/v1/alumnos/" + alumnoId + "/historial-academico")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        for (JsonNode n : lista) {
            if (MATERIA.equalsIgnoreCase(n.get("materiaNombre").asText())) return n;
        }
        return null;
    }

    private List<HistorialExcelRow> filasDelAlumno() throws IOException {
        return reader.leer(ARCHIVO).stream().filter(f -> f.dni().equals(DNI)).toList();
    }

    /** La celda de la materia en el ARCHIVO (null si esta vacia). */
    private HistorialExcelMateriaCelda enElExcel() throws IOException {
        return filasDelAlumno().stream().flatMap(f -> f.materias().stream())
                .filter(m -> m.nombreMateria().equalsIgnoreCase(MATERIA)).findFirst().orElse(null);
    }

    @Test
    void crear_modificar_eliminar_y_reintentoDeLoQueNoLlegoAlExcel() throws Exception {
        String admin = tokenAdmin();
        long alumnoId = json(mockMvc.perform(post("/api/v1/usuarios").header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dni\":\"" + DNI + "\",\"email\":\"persist55@test.com\",\"nombre\":\"Pepa\",\"apellido\":\"Persistencia\","
                                + "\"password\":\"Abcd1234\",\"roles\":[\"ALUMNO\"]}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("id").asLong();
        long materiaId = json(mockMvc.perform(post("/api/v1/materias").header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"codigo\":\"XL-PERS1\",\"nombre\":\"" + MATERIA + "\",\"cargaHoraria\":64}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("id").asLong();
        int hojas = hojas();

        // PRUEBA 1 - CREACION: base + archivo
        JsonNode creado = fijar(admin, alumnoId, materiaId, "{\"estado\":\"REGULAR\"}");
        assertThat(creado.get("excelSincronizado").asBoolean()).as(creado.toString()).isTrue();
        assertThat(enLaBase(admin, alumnoId).get("estado").asText()).isEqualTo("REGULAR");
        assertThat(filasDelAlumno()).hasSize(1);
        assertThat(filasDelAlumno().get(0).apellido()).isEqualTo("PERSISTENCIA");
        assertThat(enElExcel().estadoTexto()).isEqualTo("REGULAR");

        // PRUEBA 2 - MODIFICACION: aprobado con nota; luego se corrige. Sin duplicados y sin restos del dato viejo.
        JsonNode aprobado = fijar(admin, alumnoId, materiaId, "{\"estado\":\"APROBADA\",\"nota\":9}");
        assertThat(aprobado.get("excelSincronizado").asBoolean()).isTrue();
        assertThat(enLaBase(admin, alumnoId).get("nota").asInt()).isEqualTo(9);
        assertThat(enElExcel().estadoTexto()).isEqualTo("APROBADA");
        assertThat(enElExcel().nota()).isEqualTo(9);
        assertThat(enElExcel().fecha()).isEqualTo(LocalDate.now());
        fijar(admin, alumnoId, materiaId, "{\"estado\":\"REGULAR\"}");
        assertThat(enElExcel().estadoTexto()).isEqualTo("REGULAR");
        assertThat(enElExcel().nota()).as("la nota vieja no puede quedar en el archivo").isNull();
        assertThat(enElExcel().fecha()).isNull();
        assertThat(filasDelAlumno()).hasSize(1);
        // ...y releer el archivo no "resucita" la nota en la base
        mockMvc.perform(post("/api/v1/historial-academico/recargar").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());
        assertThat(enLaBase(admin, alumnoId).get("nota").isNull()).isTrue();

        // PRUEBA 3 - ELIMINACION (vuelve a PENDIENTE = sin registro): base y archivo
        JsonNode borrado = fijar(admin, alumnoId, materiaId, "{\"estado\":\"PENDIENTE\"}");
        assertThat(borrado.get("excelSincronizado").asBoolean()).isTrue();
        JsonNode base = enLaBase(admin, alumnoId);
        assertThat(base == null || "PENDIENTE".equals(base.get("estado").asText())).isTrue();
        assertThat(enElExcel()).as("celdas de la materia vacias").isNull();
        assertThat(filasDelAlumno()).hasSize(1);

        // Falla original: un cambio que quedo solo en la base (p. ej. el Excel estaba abierto) no se pierde.
        // Se simula: la base pasa a LIBRE "despues" de la ultima escritura del archivo, que sigue sin ese dato.
        EstadoAcademicoMateria fila = estadoRepository.findByAlumnoIdAndMateriaId(alumnoId, materiaId).orElseThrow();
        fila.setEstado(EstadoMateria.LIBRE);
        fila.setFechaActualizacion(LocalDateTime.now());
        estadoRepository.save(fila);
        Files.setLastModifiedTime(ARCHIVO, FileTime.from(Instant.now().minus(1, ChronoUnit.HOURS)));
        mockMvc.perform(post("/api/v1/historial-academico/recargar").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());
        assertThat(enLaBase(admin, alumnoId).get("estado").asText()).as("la base no se pisa con el Excel viejo").isEqualTo("LIBRE");
        assertThat(enElExcel().estadoTexto()).as("el pendiente se escribio en el archivo").isEqualTo("LIBRE");

        // Y al reves: si alguien edita el Excel DESPUES, manda el Excel
        ponerEstado("CURSANDO");
        mockMvc.perform(post("/api/v1/historial-academico/recargar").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());
        assertThat(enLaBase(admin, alumnoId).get("estado").asText()).isEqualTo("CURSANDO");

        // PRUEBA 5 - INTEGRIDAD: el archivo abre y conserva todas sus hojas tras todas las escrituras
        assertThat(hojas()).isEqualTo(hojas);
        assertThat(filasDelAlumno()).hasSize(1);
    }

    private int hojas() throws IOException {
        try (FileInputStream in = new FileInputStream(ARCHIVO.toFile()); Workbook wb = WorkbookFactory.create(in)) {
            return wb.getNumberOfSheets();
        }
    }

    /** Edicion "externa" del archivo (como si alguien lo abriera en Excel y cambiara la celda). */
    private void ponerEstado(String estado) throws IOException {
        Workbook wb;
        try (FileInputStream in = new FileInputStream(ARCHIVO.toFile())) {
            wb = WorkbookFactory.create(in);
        }
        try (wb) {
            for (Sheet sheet : wb) {
                HistorialExcelLayout layout = HistorialExcelLayout.leer(sheet);
                if (layout.getColumnaDni() < 0 || layout.columnas(MATERIA) == null) continue;
                for (int i = HistorialExcelLayout.PRIMERA_FILA_DATOS; i <= sheet.getLastRowNum(); i++) {
                    Row r = sheet.getRow(i);
                    if (r != null && DNI.equals(HistorialExcelCellUtils.texto(r.getCell(layout.getColumnaDni())))) {
                        int col = layout.columnas(MATERIA).colCursada();
                        Cell c = r.getCell(col) != null ? r.getCell(col) : r.createCell(col);
                        c.setCellValue(estado);
                    }
                }
            }
            try (FileOutputStream out = new FileOutputStream(ARCHIVO.toFile())) {
                wb.write(out);
            }
        }
    }
}
