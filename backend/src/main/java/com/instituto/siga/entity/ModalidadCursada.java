package com.instituto.siga.entity;

/** Duracion de la cursada de una materia. Las existentes (antes de este campo) son CUATRIMESTRAL. */
public enum ModalidadCursada {
    /** Se dicta en un cuatrimestre: al asociarla a un plan se indica cual (1 o 2). */
    CUATRIMESTRAL,
    /** Se dicta todo el año: en el plan no lleva cuatrimestre. */
    ANUAL
}
