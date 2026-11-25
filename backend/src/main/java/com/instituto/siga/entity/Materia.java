package com.instituto.siga.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import org.hibernate.annotations.ColumnDefault;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "materia")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Materia {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 20)
    private String codigo;

    @Column(nullable = false, length = 150)
    private String nombre;

    @Column(name = "carga_horaria", nullable = false)
    private int cargaHoraria;

    @Builder.Default
    @Column(nullable = false)
    private boolean activa = true;

    /** CUATRIMESTRAL o ANUAL. El DEFAULT de la columna completa las materias que ya existian (todas cuatrimestrales)
     * cuando el esquema se actualiza sobre una base con datos. */
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @ColumnDefault("'CUATRIMESTRAL'")
    @Column(nullable = false, length = 20)
    private ModalidadCursada modalidad = ModalidadCursada.CUATRIMESTRAL;
}
