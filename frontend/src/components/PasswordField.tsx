import { useState } from 'react';
import { IconButton, InputAdornment, TextField, Tooltip, type TextFieldProps } from '@mui/material';
import Visibility from '@mui/icons-material/Visibility';
import VisibilityOff from '@mui/icons-material/VisibilityOff';

/**
 * Campo de contraseña con un botón para mostrar u ocultar lo escrito. Arranca OCULTO y vuelve a ocultarse cada vez
 * que el formulario se abre de nuevo (el componente se monta otra vez). Es solo visual: cambia el `type` del input
 * entre password y text; el valor que se envía, la validación y el hasheo (BCrypt en el backend) no cambian.
 */
export function PasswordField({ slotProps, ...props }: Omit<TextFieldProps, 'type'>) {
  const [visible, setVisible] = useState(false);
  const etiqueta = visible ? 'Ocultar contraseña' : 'Mostrar contraseña';
  return (
    <TextField
      {...props}
      type={visible ? 'text' : 'password'}
      slotProps={{
        ...slotProps,
        input: {
          endAdornment: (
            <InputAdornment position="end">
              <Tooltip title={etiqueta}>
                <IconButton
                  aria-label={etiqueta}
                  aria-pressed={visible}
                  onClick={() => setVisible((v) => !v)}
                  onMouseDown={(e) => e.preventDefault()} // no le saca el foco al campo
                  edge="end"
                  size="small"
                >
                  {visible ? <VisibilityOff fontSize="small" /> : <Visibility fontSize="small" />}
                </IconButton>
              </Tooltip>
            </InputAdornment>
          ),
        },
      }}
    />
  );
}
