-- 1) La version de un plan de estudio admite hasta 100 caracteres (antes 10).
ALTER TABLE plan_estudio MODIFY version VARCHAR(100) NOT NULL;

-- 2) Materias anuales ademas de cuatrimestrales. Las existentes quedan CUATRIMESTRAL (el DEFAULT).
ALTER TABLE materia ADD COLUMN modalidad ENUM('CUATRIMESTRAL','ANUAL') NOT NULL DEFAULT 'CUATRIMESTRAL';
--    En el plan, una materia anual no lleva cuatrimestre (NULL). Las asociaciones existentes conservan el suyo.
ALTER TABLE plan_materia MODIFY cuatrimestre TINYINT NULL;

-- 3) Comisiones: una materia puede tener varias comisiones en la misma carrera (A, B, C...). Se reemplaza la
--    restriccion "una por materia y carrera" (V6) por "no repetir nombre y periodo en la misma materia y carrera".
--    Primero se crea el indice nuevo (empieza por materia_id: sigue sirviendo a la FK de materia) y despues se
--    quitan los viejos.
ALTER TABLE comision ADD CONSTRAINT uk_comision_materia_carrera_nombre_periodo
    UNIQUE (materia_id, carrera_id, nombre, periodo_lectivo);
ALTER TABLE comision DROP INDEX uk_comision_materia_carrera;

--    La UNIQUE (materia_id, nombre, periodo_lectivo) de V1 no tiene nombre explicito: se busca por sus columnas.
--    Sin quitarla, la misma comision "A 2026-1C" de una materia no podria existir en dos carreras.
SET @indice_v1 := (
    SELECT INDEX_NAME FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'comision' AND NON_UNIQUE = 0 AND INDEX_NAME <> 'PRIMARY'
    GROUP BY INDEX_NAME
    HAVING GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) = 'materia_id,nombre,periodo_lectivo'
    LIMIT 1);
SET @sentencia := IF(@indice_v1 IS NULL, 'SELECT 1', CONCAT('ALTER TABLE comision DROP INDEX `', @indice_v1, '`'));
PREPARE quitar_indice_v1 FROM @sentencia;
EXECUTE quitar_indice_v1;
DEALLOCATE PREPARE quitar_indice_v1;
