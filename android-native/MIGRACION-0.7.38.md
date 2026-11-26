# Migración de Balance One existente, no proyecto nuevo
Fuente fija: commit 213c548d51c14605d6dda4e460668b5733b642cf de main, index.html blob 8c5132378e923a4bd4bd966ed824b3d6bf715cea. Título Balance One 0.7.38 Beta; 23.165 líneas. La rama android-native contiene una copia de ese árbol. No cargar HTML en la APK.
## Inventario directamente del original
- 6342: barra personalizable con Inicio, Calendario, Vehículos, Tarjetas, Perfil, Suscripciones, Pagos, Metas, Calculadora, Deudas.
- 6392–6462: amfinance-demo-v1, movimientos/pagos/vehiculos/tarjetas, migraciones.
- 6582–6600: preferencias de copia; idioma, moneda, formato, diseños, orden, fuente, tamaño, tema, color, forma, progreso.
- 6979–7420: sincronización Firebase, validación de snapshot, claves locales, revisiones, conflictos, recuperación, autenticación. 7121–7163: fragmentos de 160000 caracteres y escritura transaccional.
- 10525–10839: gastos, categorías, pagos, resumen mensual; no sustituir por suma ingenua de movimientos.
- 11286 y 12033: inicio clásico y nuevo, dinero disponible, ingresos/gastos por periodo, cuentas visibles, avisos y cobros.
- 12335–15749: pagos, suscripciones, calendario, servicios y préstamos.
- 15937–16633: fichas vehículo, fotos, modelos, financiación, bitácora y mantenimiento.
- 16646–17491: cuentas, metas, operaciones, transferencias; clave amfinance-cuentas-banco.
- 17708–19499: tarjetas, imágenes, intereses, recompensas, pagos, cuotas y vistas.
- 19598–19743: copia JSON v1/v2, datos, cuentasBanco, preferencias.
- 21209–21235: respaldo cifrado PBKDF2 SHA-256 600000 iteraciones, AES-GCM 256, sal 16, IV 12, base64.
- 21309–21781: movimientos, recibos, favoritos, proyectos, origen de pago, actualización de cuentas y tarjetas. Mantener transacciones atómicas e IDs originales.
## Primer traslado de la versión real
OriginalBackup interpreta la estructura de copias v1/v2 y descifra el formato original. MigrationApp es la pantalla de arranque nativa, con navegación original y lectura de cuentas, movimientos, tarjetas, vehículos, metas, suscripciones y pagos a partir de una copia real; es una vista de verificación temporal, no paridad ni restauración persistente. Conserva el objeto completo en memoria, no altera el archivo ni Firebase. El CRUD genérico anterior queda fuera de la entrada principal para no confundirlo con los datos reales. No se ha compilado ni probado.
## Siguiente trabajo
Portar modelos, invariantes financieras y pruebas de equivalencia usando fixtures sintéticos del esquema original. Revisar backups cifrados en dispositivo, luego persistencia transaccional de todos los campos y seguridad local. Portar UI con los diseños/activos originales por módulo; luego lectura y escritura Firebase compatible con revisiones y autenticación. No conectar datos reales ni escribir a la nube antes de validar. No declarar una APK completa hasta alcanzar paridad.
