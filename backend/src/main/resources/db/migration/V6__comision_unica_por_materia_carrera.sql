-- Regla institucional: UNA sola comision por materia y carrera (la misma materia puede tener otra comision
-- en otra carrera). carrera_id queda NULL-able solo para no romper filas viejas; el servicio siempre la exige.

ALTER TABLE comision ADD COLUMN carrera_id BIGINT NULL;
ALTER TABLE comision ADD CONSTRAINT fk_comision_carrera FOREIGN KEY (carrera_id) REFERENCES carrera(id);

-- Completa la carrera solo cuando no hay dudas: la materia esta en UNA carrera, y solo para la primera comision
-- de esa materia (si habia varias, las demas quedan sin carrera para que el administrador decida).
UPDATE comision c
JOIN (SELECT pm.materia_id, MIN(pe.carrera_id) AS carrera_id
      FROM plan_materia pm
      JOIN plan_estudio pe ON pe.id = pm.plan_id
      GROUP BY pm.materia_id
      HAVING COUNT(DISTINCT pe.carrera_id) = 1) unica ON unica.materia_id = c.materia_id
JOIN (SELECT materia_id, MIN(id) AS id FROM comision GROUP BY materia_id) primera ON primera.id = c.id
SET c.carrera_id = unica.carrera_id;

ALTER TABLE comision ADD CONSTRAINT uk_comision_materia_carrera UNIQUE (materia_id, carrera_id);
