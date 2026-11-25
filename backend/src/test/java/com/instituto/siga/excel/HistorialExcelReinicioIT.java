package com.instituto.siga.excel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.instituto.siga.SigaApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.FileInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PRUEBA 4: persistencia despues de REINICIAR el backend, sin simulaciones. Se levanta la aplicacion completa
 * (perfiles por defecto "dev,local": base H2 en archivo + Excel en disco, ambos en una carpeta temporal), se crean
 * datos por HTTP, se DETIENE la aplicacion, se vuelve a iniciar sobre los mismos archivos y se comprueba que la base
 * y el Excel conservan el dato, que el arranque no re-siembra data.sql (fallaria por DNI duplicado) y que la
 * sincronizacion al iniciar no duplica filas.
 */
class HistorialExcelReinicioIT {

    private static final String DNI = "99000444";
    private static final String MATERIA = "FISICA";

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    @TempDir
    Path dir;

    private ConfigurableApplicationContext iniciar() {
        return new SpringApplicationBuilder(SigaApplication.class)
                .profiles("dev", "local")
                // argumentos de linea de comandos: tienen prioridad sobre los .yml (incluido el de tests)
                .run("--server.port=0",
                        "--app.db.archivo=" + dir.resolve("siga-db").toAbsolutePath(),
                        "--app.historial.excel-path=" + dir.resolve("historial-academico.xlsx").toAbsolutePath());
    }

    private String base(ConfigurableApplicationContext ctx) {
        return "http://localhost:" + ctx.getEnvironment().getProperty("local.server.port") + "/api/v1";
    }

    private JsonNode pedir(String metodo, String url, String token, String cuerpo) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json");
        if (token != null) b.header("Authorization", "Bearer " + token);
        b.method(metodo, cuerpo == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(cuerpo));
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(metodo + " " + url + " -> " + r.body()).isBetween(200, 299);
        return r.body().isBlank() ? null : json.readTree(r.body());
    }

    private String login(String base) throws Exception {
        return pedir("POST", base + "/auth/login", null, "{\"dni\":\"30111222\",\"password\":\"Admin1234\"}")
                .get("accessToken").asText();
    }

    private List<HistorialExcelRow> filasDelAlumno() throws Exception {
        return new HistorialExcelReader().leer(dir.resolve("historial-academico.xlsx")).stream()
                .filter(f -> f.dni().equals(DNI)).toList();
    }

    @Test
    void elDatoSigueEnLaBaseYEnElExcelDespuesDeReiniciar() throws Exception {
        long alumnoId;
        // ---------- primer arranque: base nueva (se siembra data.sql) y Excel nuevo (desde la plantilla)
        try (ConfigurableApplicationContext ctx = iniciar()) {
            String base = base(ctx);
            String admin = login(base);
            alumnoId = pedir("POST", base + "/usuarios", admin, "{\"dni\":\"" + DNI + "\",\"email\":\"reinicio44@test.com\","
                    + "\"nombre\":\"Rita\",\"apellido\":\"Reinicio\",\"password\":\"Abcd1234\",\"roles\":[\"ALUMNO\"]}").get("id").asLong();
            long materiaId = pedir("POST", base + "/materias", admin,
                    "{\"codigo\":\"XL-REIN1\",\"nombre\":\"" + MATERIA + "\",\"cargaHoraria\":64}").get("id").asLong();
            JsonNode r = pedir("PUT", base + "/alumnos/" + alumnoId + "/estado-academico/" + materiaId, admin,
                    "{\"estado\":\"APROBADA\",\"nota\":8}");
            assertThat(r.get("excelSincronizado").asBoolean()).as(r.toString()).isTrue();
        }
        // ---------- backend detenido: el archivo tiene el dato
        assertThat(Files.exists(dir.resolve("siga-db.mv.db"))).as("base persistida en disco").isTrue();
        assertThat(filasDelAlumno()).hasSize(1);
        assertThat(filasDelAlumno().get(0).materias()).singleElement().satisfies(c -> {
            assertThat(c.estadoTexto()).isEqualTo("APROBADA");
            assertThat(c.nota()).isEqualTo(8);
        });

        // ---------- segundo arranque sobre los mismos archivos
        try (ConfigurableApplicationContext ctx = iniciar()) {
            String base = base(ctx);
            String admin = login(base);
            JsonNode historial = pedir("GET", base + "/alumnos/" + alumnoId + "/historial-academico", admin, null);
            JsonNode fisica = null;
            for (JsonNode n : historial) {
                if (MATERIA.equalsIgnoreCase(n.get("materiaNombre").asText())) fisica = n;
            }
            assertThat(fisica).as("el dato sigue en la base tras reiniciar").isNotNull();
            assertThat(fisica.get("estado").asText()).isEqualTo("APROBADA");
            assertThat(fisica.get("nota").asInt()).isEqualTo(8);
        }
        // ---------- y el Excel sigue igual: sin filas duplicadas, el archivo abre con todas sus hojas
        assertThat(filasDelAlumno()).hasSize(1);
        assertThat(filasDelAlumno().get(0).materias().get(0).estadoTexto()).isEqualTo("APROBADA");
        try (FileInputStream in = new FileInputStream(dir.resolve("historial-academico.xlsx").toFile());
             org.apache.poi.ss.usermodel.Workbook wb = org.apache.poi.ss.usermodel.WorkbookFactory.create(in)) {
            assertThat(wb.getNumberOfSheets()).isEqualTo(9);
        }
    }
}
