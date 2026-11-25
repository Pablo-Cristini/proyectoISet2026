package com.instituto.siga.config;

import com.instituto.siga.excel.HistorialExcelPath;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.List;

/**
 * Perfil {@code local} (activo por defecto junto con {@code dev}): la base H2 se guarda en un ARCHIVO
 * ({@code data/siga-db.mv.db} en la raiz del proyecto) en vez de en memoria, asi lo que se crea desde la
 * interfaz (usuarios, carreras, materias, mesas, inscripciones, notas) sobrevive a cerrar y volver a abrir
 * el backend. Antes el perfil dev usaba {@code jdbc:h2:mem} + {@code create-drop}: cada arranque borraba todo.
 *
 * <p>La ruta relativa se ancla igual que el Excel del historial ({@link HistorialExcelPath}): da lo mismo
 * arrancar desde {@code backend/} o desde la raiz, siempre se usa el mismo archivo. {@code SIGA_DB_PATH}
 * permite otra ubicacion (sin la extension {@code .mv.db}).
 *
 * <p>Los datos demo de {@code data.sql} se cargan solo la primera vez (base vacia): con un archivo
 * persistente, correrlo en cada arranque duplicaria filas o fallaria por DNI duplicado.
 *
 * <p>{@code ddl-auto: update} crea tablas y columnas nuevas, pero NO agranda columnas, ni las vuelve opcionales, ni
 * quita restricciones. Esos cambios (los mismos que hace Flyway en produccion, V7) se aplican aca al iniciar, de
 * forma idempotente, para que una base local creada con una version anterior quede igual que una nueva.
 */
@Slf4j
@Configuration
@Profile("local")
public class LocalDatabaseConfig {

    @Bean
    public DataSource dataSource(DataSourceProperties properties,
                                 @Value("${app.db.archivo:./data/siga-db}") String archivo) {
        Path ruta = new HistorialExcelPath(archivo).resolver();
        // AUTO_SERVER: si quedo abierto otro backend (u otra consola H2) no falla con "database already in use"
        properties.setUrl("jdbc:h2:file:" + ruta.toString().replace('\\', '/') + ";MODE=MySQL;AUTO_SERVER=TRUE");
        log.info("Base de datos local persistente: {}.mv.db", ruta);
        return properties.initializeDataSourceBuilder().build();
    }

    /** Cambios de esquema que {@code ddl-auto: update} no aplica sobre una base existente (idempotentes). */
    static final List<String> AJUSTES_DE_ESQUEMA = List.of(
            // version del plan: hasta 100 caracteres (antes 10)
            "ALTER TABLE plan_estudio ALTER COLUMN version SET DATA TYPE VARCHAR(100)",
            // materias anuales: en el plan no llevan cuatrimestre
            "ALTER TABLE plan_materia ALTER COLUMN cuatrimestre SET NULL",
            // varias comisiones por materia y carrera: se quita la restriccion "una por materia y carrera"
            "ALTER TABLE comision DROP CONSTRAINT IF EXISTS uk_comision_materia_carrera");

    /** Corre antes del ApplicationReadyEvent, o sea antes de que se re-importe el Excel del historial. */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public ApplicationRunner sembrarDatosDemoSiLaBaseEstaVacia(DataSource dataSource) {
        return args -> {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            for (String ajuste : AJUSTES_DE_ESQUEMA) {
                try {
                    jdbc.execute(ajuste);
                } catch (RuntimeException e) {
                    log.error("No se pudo aplicar el ajuste de esquema de la base local [{}]: {}. Si persiste, borrá "
                            + "data/siga-db.mv.db para recrearla (se pierden los datos locales)", ajuste, e.getMessage());
                }
            }
            Integer roles = jdbc.queryForObject("SELECT COUNT(*) FROM rol", Integer.class);
            if (roles != null && roles > 0) {
                log.info("Base local con datos existentes: no se re-siembra data.sql");
                return;
            }
            ResourceDatabasePopulator populator = new ResourceDatabasePopulator(new ClassPathResource("data.sql"));
            populator.setSqlScriptEncoding("UTF-8");
            populator.execute(dataSource);
            log.info("Base local nueva: se cargaron los datos demo de data.sql");
        };
    }
}
