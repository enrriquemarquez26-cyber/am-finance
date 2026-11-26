# Balance One Android nativo — continuidad
Fecha: 2026-11-26. Rama: android-native. Carpeta: android-native/.
Repositorio: https://github.com/enrriquemarquez26-cyber/am-finance
Objetivo: APK Android auténtica, Kotlin + Jetpack Compose + Room, sin WebView, HTML, PWA ni cargar el sitio web. Mantener la web en main intacta. No introducir cambios destructivos ni migrar datos sin copia y validación.
## Corrección de alcance (prioridad absoluta)
El usuario exige portar exactamente Balance One existente 0.7.38 Beta, no rehacer una app simplificada. Se recuperó el index.html completo mediante fetch_blob; referencia y mapa de funciones en MIGRACION-0.7.38.md. La rama mantiene la fuente original. La pantalla de arranque ahora es MigrationApp (lector nativo de copias originales), no el CRUD genérico. La vista de comprobación es temporal y de solo lectura, aún sin paridad, persistencia ni sincronización. No se han probado las copias ni compilado este cambio.
## Entregado en el primer paso técnico (0.1.0-native, prototipo no definitivo)
- Proyecto Gradle, Activity Compose, interfaz Inicio/Movimientos/Ajustes, colores y formas iniciales, icono provisional.
- Alta, edición, eliminación confirmada de ingresos/gastos, balance registrado, listado reciente y persistencia Room en centavos enteros USD.
- Preferencia claro/oscuro/sistema. No hay datos de ejemplo ni acceso a internet.
- Identificador provisional separado com.balanceone.nativeapp para no reemplazar la instalación anterior.
## Estado de verificación
Código inicial escrito. No se ha compilado ni probado en dispositivo desde el entorno de chat: no dispone de Android SDK/Gradle. No afirmar que la APK existe hasta comprobar un artefacto de compilación.
## Compilación automatizada
Se agregó .github/workflows/android-native.yml en esta rama para compilar una APK debug con GitHub Actions y publicarla como artefacto Balance-One-native-debug. Al verificar, no había ejecuciones registradas todavía. Consultar https://github.com/enrriquemarquez26-cyber/am-finance/actions antes de declarar una compilación exitosa; si no arranca, revisar permisos y configuración de Actions o compilar localmente. workflow_dispatch puede requerir que el workflow exista en la rama predeterminada: no modificar main sin autorización. Aún no hay pruebas unitarias escritas.
## Pendientes, en orden
1. Compilar assembleDebug con Gradle 8.9 y JDK 17, SDK 35; corregir errores; instalar en Android y probar CRUD, reinicio, orientación, tema y modo avión.
2. Continuar auditoría ya iniciada en MIGRACION-0.7.38.md; extraer modelos, invariantes y diseños completos del blob original, probar compatibilidad de copias y portar módulos sin perder funciones. No adivinar credenciales ni esquema.
3. Replicar el diseño real de Inicio, barra flotante, cuentas, tarjetas, vehículos, calendario, metas, pagos, recompensas y demás ajustes. No confundir prototipo con paridad.
4. Implementar migración de datos validada y sincronización compartida: IDs estables, conflictos, cola offline, autenticación, seguridad. No borrar datos web ni locales. Verificar privacidad antes de usar datos financieros reales.
5. Pruebas, accesibilidad, icono original, widget nativo, biometría si procede, firma y APK release. Mantener Free/Pro según web; no activar cobros sin aprobación.
## Para continuar en otro chat
Pega: «Continúa Balance One Android nativo desde la rama android-native de enrriquemarquez26-cyber/am-finance. Lee android-native/CONTINUIDAD.md y el último commit, comprueba qué existe y qué se ha probado; retoma la primera tarea pendiente. Actualiza CONTINUIDAD.md con cada avance. No toques main ni uses WebView».
