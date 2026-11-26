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


## Continuación — segunda sesión, 2026-11-26
- Se verificó la rama existente y el blob original de 23.165 líneas. No se modificó main.
- OriginalSummary.kt porta la regla de gastos del mes (10525–10564: excluye los pagos de tarjetas para evitar duplicar gastos) y el resumen de metas (16866–16912: aportes menos retiros o saldo de cuenta de ahorro vinculada). Usa centavos y no inventa cero si el cálculo falla.
- MigrationApp muestra el gasto del mes y el progreso de metas en la vista nativa de comprobación, aún de solo lectura; no representa todavía el diseño completo original.
- Se añadieron pruebas JVM con datos sintéticos: exclusión de abonos de tarjeta, fechas inválidas, metas manuales/vinculadas, cuenta ausente, respaldo v1/v2 y campos desconocidos, cifrado compatible y contraseña incorrecta. La dependencia JSON es exclusiva de tests. El lector usa java.util.Base64 (minSdk 26).
- Último cambio de código: 1b61759e846db8991f29286e94e62b76b274a4c1. Pruebas escritas, NO ejecutadas; no hay SDK Android ni Gradle en este entorno. NO afirmar que hay APK disponible.
- Retomar verificando GitHub Actions y los tests; corregir fallos antes de instalar. Después portar persistencia local segura del snapshot completo, con confirmación de importación, cifrado local, recuperación y sin escritura Firebase. Continuar los diseños y las invariantes originales por módulo. No activar sincronización ni usar datos reales hasta validar.

## Continuación — tercera sesión, 2026-11-26
- Se verificó la rama contra main antes de trabajar: solo archivos Android y workflow añadidos. No se tocó main ni la web.
- Se consultaron los checks del último commit de código anterior y el listado de ejecuciones PR: ambos vacíos. Ese listado no cubre ejecuciones push; no se pudo confirmar el estado general de Actions. No hay APK verificada ni SDK/Gradle local.
- LocalSnapshotStore.kt guarda el documento completo cifrado AES-256/GCM con clave Android Keystore y archivo privado AtomicFile en noBackupFilesDir, sin red. Conserva campos aún no portados. Recupera al abrir; no crea claves nuevas al intentar leer una copia cuyo identificador se perdió. El respaldo original no se modifica.
- MigrationApp.kt ahora valida y pide confirmación para importar/reemplazar, restaura la copia al iniciar y permite eliminar únicamente la copia local con confirmación. Se añadieron validaciones de arrays opcionales usados en la interfaz en OriginalBackup.kt. Último commit de código: cf81028dcefd717f18c09c8317db3267d0b53919. Código revisado estáticamente, NO compilado ni probado en Android. No usar datos reales hasta probar.
- Retomar: verificar compilación y tests previos en Actions o Android Studio; escribir y ejecutar pruebas instrumentadas del almacenamiento (importar, reiniciar, cancelar reemplazo, reemplazar, error de clave/cifrado, recuperación tras escritura fallida, eliminar, modo avión) con datos sintéticos. Verificar escritura/lectura y fidelidad de la totalidad del respaldo. Después portar edición y diseño real por módulo, empezando por Inicio, cuentas y movimientos, siguiendo el index original y preservando invariantes. Sincronización sigue pendiente; no escribir Firebase ni main. La versión 0.7.38 Beta original permanece como fuente de verdad; la interfaz Android sigue provisional y de solo lectura.

## Continuación — APK compilada y migración 0.2.0-native, 2026-11-26
- La primera APK 0.1.0-native SÍ fue compilada y pasó el comando de tests en Actions, ejecución 45767367336, artefacto 15093310244. Corrigió sdkmanager ausente y el paquete obsoleto tools configurando setup-android con paquetes específicos. No se ha verificado instalación/dispositivo.
- En esta sesión se portaron reglas de panorama (lunes a domingo, quincena 1–15/16–fin, mes), saldo de cuentas cheques/todas, ingresos y gastos hasta hoy sin duplicar abonos de tarjeta; calendario interactivo con tres vistas y movimientos/pagos registrados; creación/edición/eliminación de movimientos bancarios ordinarios, ajustes de saldo atómicos en el snapshot, bloqueo de registros especiales y duplicados; creación/edición de cuentas y correcciones de saldo con historial; exportación cifrada portátil compatible con el formato web original. Se conservaron campos desconocidos. La interfaz todavía es provisional y no hay sincronización.
- Se añadieron pruebas JVM con datos sintéticos de altas/ediciones/reversiones, periodos, insuficiencia, duplicados, cuentas y cifrado. La versión ahora es 0.2.0-native (versionCode 2), último commit de código/config 2dc55e851f26fd5bc9d402b29dc125f47d6cba94. Compilación de los cambios nuevos aún no confirmada; buscar ejecución del commit más reciente y comprobar tests/artefacto. No usar datos reales hasta validar en dispositivo. No tocar main.
- Pendiente: firma estable para actualizar sin perder datos (APK debug de runners distintos puede tener otra clave; nunca indicar desinstalación sin respaldo), pruebas instrumentadas del almacén y exportación; portar diseño original e imágenes, tarjeta crédito/pagos/cuotas/recompensas, vehículos/mantenimiento/fotos, metas edición y aportes, transferencias, suscripciones/cobros, calendario de vencimientos, préstamos, calculadora, voz, recibos, ajustes completos, avisos y sincronización Firebase compatible con conflictos y autenticación; no introducir escrituras nube hasta validar. Paridad NO alcanzada.

## Continuación — pruebas de almacenamiento Android, 2026-11-26
- Se volvió a comprobar la primera ejecución exitosa 45767367336 y su artefacto 15093310244. Las ejecuciones de 0.2.0-native 45768563959 y 45768583510 aún estaban en curso al empezar esta sesión.
- Se añadieron pruebas instrumentadas AndroidJUnit4 del almacén cifrado con datos sintéticos y un directorio aislado: recuperación de campos desconocidos, reemplazo, eliminación, reversión de escritura interrumpida y rechazo de manipulación del cifrado. No se toca la copia real ni se elimina su clave.
- Gradle configura el runner y dependencias AndroidX Test; Actions compila también el APK de pruebas y lo publica por separado. Esto NO ejecuta las pruebas instrumentadas: ejecutar en emulador/dispositivo mediante gradle :app:connectedDebugAndroidTest. La compilación nueva tampoco se ha confirmado todavía.
- Mantener 0.2.0-native hasta verificar. Siguiente paso: revisar ejecución más reciente, corregir fallos, ejecutar instrumentación, luego portar transferencias, tarjetas, metas y diseños fieles según el original. Falta firma estable, no desinstalar una copia existente sin respaldo. No usar datos reales ni tocar main.

## Continuación — operaciones y metas, 2026-11-26
- Portadas reglas del index original 16912–16972 y 17162–17210: depósitos, retiros, transferencias con historial pareado y saldo atómico; no crean ingresos/gastos. Metas: alta, edición, borrado confirmado, vinculación única a ahorro, desvinculación preservando saldo, aportes y reducciones manuales e historial con eliminación confirmada.
- NativeOperations.kt, OperationDialogs.kt y MigrationApp integran las operaciones con el almacén cifrado local. Pruebas unitarias nuevas con datos sintéticos. No hay llamadas bancarias ni Firebase.
- Aún sin confirmar compilación de la rama 0.2.0 ni ejecución de instrumentación. Falta portar tarjetas/pagos/cuotas, vehículos, suscripciones, préstamos, calculadora, diseños y activos, sincronización, firma estable y pruebas. No tocar main.

## Continuación — tarjetas y vehículos, 2026-11-26
- NativeCredit.kt y DebtDialogs.kt implementan altas y edición de datos básicos de tarjeta/vehículo, pago registrado con origen bancario o crédito, capital/interés del vehículo conforme al cálculo original, control de deuda y crédito disponible, vínculo con meta de pago ya existente. UI incluye registro de pagos confirmado y cálculo de crédito disponible.
- Se añadieron tests sintéticos de pagos, principal/interés y validaciones. No hay pagos reales ni conexión a banco.
- Pendientes: edición/eliminación reversible de pagos, cuotas, recompensas, metas de pago editables, compras con tarjeta, fotos y diseños, mantenimiento, suscripciones, préstamos, calculadora, avisos, sincronización, firma y pruebas. No afirmar paridad. No tocar main.

## Continuación — arranque offline y pruebas reales en emulador, 2026-11-26
- Se verificó el éxito de la ejecución 45768713093 (compilación, tests JVM, subida de artefactos); corresponde a un estado anterior de la rama, no al último código.
- Consultado directamente el blob original, líneas 6392–6406 y 19598–19621: OriginalBackup.empty() crea el documento vacío v2 con cuentas y preferencias sin datos ficticios. LocalSnapshotStore.createEmpty() protege contra sobrescritura de copias existentes, dañadas o escrituras pendientes; MigrationApp ofrece abrir respaldo o empezar sin datos y crear la primera cuenta desde Inicio. Se añadió prueba Android de inicio vacío y rechazo de sustitución de una copia existente o dañada.
- Workflow ahora añade trabajo separado connectedDebugAndroidTest sobre emulador API 35 con datos sintéticos y publicación de informes. No se ha verificado aún el resultado de esta ejecución. Último commit técnico bdf15317e3d0ddc1c2a4eac2d48bf4db2bec93a5. Sin SDK local; no afirmar APK nueva, ejecución de pruebas Android ni funcionamiento probado hasta comprobar Actions.
- Próximo: verificar ambos trabajos de Actions, corregir errores; continuar módulos de pagos, suscripciones, préstamos, tarjetas y vehículos y paridad visual. Sincronización Firebase sigue pendiente: implementar compatibilidad con 6979–7420 del original, autenticación, revisiones y conflictos, sin escrituras reales antes de validar. Firma estable pendiente. Main no modificado.

## Continuación — interfaz de suscripciones y calendario
- Integrado el modelo NativeSubscriptions en la pantalla Suscripciones con alta/edición, pausa/reanudación, cancelación confirmada y facturas de servicios; préstamos/cuotas recurrentes exigen fecha de término. Se muestran cobros previstos separados de pagos reales en calendario. No se altera el saldo por programar un cobro.
- Se añadieron pruebas JVM de recurrencia, pausa, factura variable, fecha final obligatoria y conservación del documento. Pendiente comprobar compilación y pruebas de esta revisión en Actions. Interfaz aún provisional; préstamos especializados, abonos de suscripción, cuotas, recompensas, vehículos completos, paridad visual, firma estable y sincronización Firebase pendientes. No tocar main.

## Continuación — diagnóstico y corrección del fallo de CI
- Las ejecuciones 45769644925 y 45770048810 fallaron por NativeCreditTest líneas 17 y 26: org.json.JSONException al leer un número JSON mediante getString en la biblioteca org.json JVM, que exige una cadena. Los tests ahora leen el valor JSON y lo convierten a texto para la validación en centavos. No se omiten pruebas ni se modifica la lógica de pagos para pasar CI. Las pruebas instrumentadas de almacenamiento de la ejecución 45769644925 finalizaron correctamente.
- Se publica el informe JVM incluso en caso de fallo para diagnosticar futuras regresiones; la APK sigue condicionada a que las pruebas pasen. Nueva ejecución pendiente de verificar. No tocar main.

## 0.2.1 Beta · etapa de cuentas y pagos
- Base verificada 345d326, ejecución 42 (45828630443). El registro de la 41 confirma el error Kotlin de MigrationApp.kt:391. En la 42, assembleDebug y testDebugUnitTest pasaron y el emulador Android 15 completó cinco pruebas. Artefacto debug de la 42: 15120849739. Estas pruebas pertenecen a 0.2.0-native.
- Edición y eliminación confirmada de abonos de tarjeta en Pagos. La operación aplica las diferencias netas de deuda y origen, conserva el ID y la meta y guarda una sola copia. Eliminación confirmada de cuentas sin vínculos y soporte de saldos bancarios negativos importados en el total disponible. Icono original de 192 px, versión visible 0.2.1 Beta y mismo applicationId.
- Pendiente comprobar esta etapa en CI y en la interfaz. No hay escrituras en Firebase. El workflow existente produce una firma debug por runner. Para actualizar una instalación previa se necesita verificar su certificado y disponer de su clave de firma.

## 0.2.2 Beta · cobros registrados, fotografías y calculadora
- Etapa anterior bc73259 verificada en ejecución 43 (45830134948), trabajos build e instrumentación correctos.
- Se incorporó el registro por vencimiento desde Calendario. Guarda un gasto asociado a la suscripción y su fecha, ajusta banco o tarjeta y deja de mostrarlo como previsto. Nueva comprobación de duplicados. Movimientos permite compras con tarjeta, cambiar origen, revertir compras ordinarias y filtrar por texto y fechas.
- Calculadora con préstamo, hipoteca, amortización de tarjeta y proyección de ahorro conforme a calcularSimulacion. Se añadieron casos JVM para intereses y último pago parcial.
- Recursos originales de débito, crédito y vehículos empaquetados para uso sin red. Fotos personalizadas y recibos en JPEG compatible con la web, ficha de vehículo, financiación e historial. Ediciones ordinarias comprueban que la copia de origen sigue vigente. Prueba de dos editores sobre una copia aislada.
- Instrumentación de navegación añadida. CI desactiva Wi-Fi y datos móviles del emulador antes de los tests. La firma release admite un keystore externo mediante BALANCE_ONE_KEYSTORE, BALANCE_ONE_STORE_PASSWORD, BALANCE_ONE_KEY_ALIAS y BALANCE_ONE_KEY_PASSWORD. No se dispone de la clave anterior ni se ha verificado actualización sobre la instalación del usuario.
- La paridad completa sigue pendiente (widgets y modelos, préstamos, cuotas, cashback, notificaciones, voz, biometría y ajustes restantes). El contrato Firestore de la web captura strings de localStorage y una revisión fragmentada de hasta 100 partes de 160000 unidades UTF-16 dentro de una transacción. Esto difiere del JSON de exportación. No se habilitó sincronización ni se escribió en Firebase. Consultar el resultado de CI de esta etapa antes de considerarla verificada.

## Continuación de 0.2.2 Beta · préstamos, reembolsos, cuotas y servicios
- Sobre f2e04bf, se conectaron metas de pago con selección de abonos, planes de cuotas con compras vinculadas y reembolsos por tarjeta. El catálogo original funciona sin red. Los canjes se registran contra el saldo confirmado y conservan el origen de la recompensa.
- Alta de préstamos, estimaciones, cuotas parciales con capital e interés y recálculo de vencimientos. Registro de gastos de vehículos, entradas sin coste, millaje y programación del próximo servicio.
- Inicio muestra metas de ahorro, saldos de tarjetas y datos de financiación de los dos primeros vehículos. Perfil permite elegir moneda y formato. Se mantuvieron la calculadora, los cobros desde Calendario, los recursos originales, los recibos y las protecciones de guardado de la revisión anterior.
- A petición del usuario no se compiló ni se ejecutaron pruebas ni se revisó la APK. Siguen pendientes la sincronización Firebase validada, los modelos y ajustes visuales restantes, notificaciones, voz, biometría, edición avanzada del historial de vehículos y la actualización remota del catálogo.

## 0.2.3 Beta · Inicio · selección de diseño, orden y visibilidad
- Inicio > Personalizar permite elegir Clásico o Panorama financiero, reordenar y ocultar widgets por diseño. La confirmación guarda todo mediante el almacén cifrado y conserva las preferencias originales de la copia web.
- El clásico incluye el resumen de deudas y los presupuestos importados. Panorama muestra el balance mensual y las principales categorías. Se conservaron accesos a movimientos, cuentas, tarjetas, vehículos y metas. Quedan pendientes otras vistas, estilos y ajustes del navegador. Cambio de código sin compilación ni pruebas por indicación del usuario.

## Versionado de Android
En cada entrega que cambie la aplicación, incrementar la versión visible y el versionCode en app/build.gradle.kts e indicar la versión en estas notas. La personalización de Inicio corresponde a 0.2.3 Beta (versionCode 5).

## 0.2.4 Beta · presupuestos mensuales (versionCode 6)
- Inicio clásico permite agregar, cambiar de categoría, editar el límite y eliminar un presupuesto con confirmación. Acepta las categorías personalizadas importadas. El límite se guarda en datos.presupuestos, en centavos, y se aplica cada mes.
- El widget muestra los tres presupuestos con mayor proporción usada, el importe disponible o excedido y los estados del 80 % y del límite. Ver todos despliega los restantes. Los abonos de tarjeta siguen excluidos del gasto.
- Trabajo sobre el código fuente. Por indicación del usuario, sin compilación, pruebas ni revisión de APK.

## 0.2.5 Beta · categorías personales (versionCode 7)
- Perfil y Movimientos permiten crear categorías de gasto, elegir su icono y cambiar nombre o icono. Se conserva el identificador al editar, se validan nombres y alias y se mantiene el formato de categoriasPersonales de la web.
- El formulario de gastos conserva la entrada de texto y permite elegir categorías del catálogo. Movimientos busca por nombre visible y muestra el icono de las categorías personales. Presupuestos, el resumen por categoría y el cálculo de reembolsos usan el catálogo compartido.
- Sin compilar ni ejecutar pruebas ni abrir la APK por indicación del usuario.

## 0.2.6 Beta · gastos favoritos (versionCode 8)
- Movimientos permite añadir gastos al catálogo favoritosGasto y quitar plantillas. Nuevo movimiento permite reutilizar nombre, categoría y origen de un favorito desde Inicio o Movimientos. Una cuenta ausente se debe volver a elegir. El importe y la fecha del formulario se conservan. Guardar el movimiento sigue siendo una acción explícita.
- Se conserva el formato de copias de la web y la equivalencia de nombres para detectar duplicados. Gestionar una plantilla no cambia transacciones ni saldos. Sin compilar, ejecutar pruebas ni abrir la APK por indicación del usuario.

## 0.2.7 Beta · duplicar un gasto del historial
- Portado desde index.html web 0.7.38 Beta: gastoDuplicable, abrirMovVentana(copia), valorOrigenMovimiento, firmaGasto y la confirmación en guardarMovimientoBanco. El botón Duplicar abre un alta con nombre, importe, categoría y origen disponible, y fecha de hoy. Recibo, proyecto, vínculos y cuotas empiezan vacíos. Si coincide la firma de un gasto, el usuario puede volver al borrador o confirmar que lo desea registrar también.
- Cada entrega de Android debe identificar la función correspondiente en la web. Versión 0.2.7 Beta, versionCode 9. Sin compilar, ejecutar pruebas ni abrir la APK por indicación del usuario.

## 0.2.8 Beta · Inicio

Portados a Compose el carrusel de transacciones, los selectores de ingresos y cuentas, el panorama de gastos con detalle diario, la organización de cuentas, los vencimientos y los controles de privacidad y plegado. Se amplió el widget de vehículos en Inicio. La prioridad es completar Inicio antes de continuar con la pantalla Autos y el resto de las secciones. La paridad visual y el asistente de Inicio siguen pendientes. Sin pruebas ni revisión del APK por indicación del propietario.

## 0.2.9 Beta · Inicio

- Asistente desde el micrófono de Inicio. Acepta texto y dictado local en dispositivos compatibles, consulta saldos, gastos y vencimientos, abre secciones y prepara un movimiento para revisar en el formulario existente. La lectura de importes respeta la privacidad y el modo discreto.
- Recordatorio para exportar la copia cifrada si nunca se confirmó o han pasado 30 días. Actualiza la fecha únicamente después de escribir el archivo.
- Metas enlazadas con ahorro en Panorama, orden de tarjetas en Inicio clásico y bienvenida animada en los paneles de disponible e ingresos.
- Pendiente en Inicio: terminar de trasladar los detalles visuales de los dos diseños y las interacciones restantes del original. Después sigue Autos. No se compiló ni se ejecutaron pruebas.
