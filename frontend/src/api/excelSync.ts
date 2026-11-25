/**
 * Campos que agregan las respuestas de operaciones que también actualizan el Excel del historial
 * (data/historial-academico.xlsx). excelSincronizado: true = el Excel quedó al día (escrito y verificado);
 * false = el cambio quedó solo en la base (excelMensaje dice por qué); null/ausente = no había nada que escribir.
 */
export interface ResultadoExcel {
  excelSincronizado?: boolean | null;
  excelMensaje?: string | null;
}

/** Texto para avisarle al usuario que el Excel no se actualizó, o null si no hace falta avisar. */
export function avisoExcel(r: ResultadoExcel): string | null {
  return r.excelSincronizado === false
    ? `Guardado en el sistema, pero NO en el Excel del historial: ${r.excelMensaje ?? 'motivo desconocido'}`
    : null;
}
