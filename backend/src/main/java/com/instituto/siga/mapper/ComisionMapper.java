package com.instituto.siga.mapper;

import com.instituto.siga.dto.response.ComisionResponse;
import com.instituto.siga.entity.Comision;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring", uses = HorarioMapper.class)
public interface ComisionMapper {

    @Mapping(source = "materia.id", target = "materiaId")
    @Mapping(source = "materia.nombre", target = "materiaNombre")
    @Mapping(source = "carrera.id", target = "carreraId")
    @Mapping(source = "carrera.nombre", target = "carreraNombre")
    ComisionResponse toResponse(Comision entity);
}
