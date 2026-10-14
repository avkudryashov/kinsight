# Política de privacidad

**Kinsight: tiempo de pantalla** (paquete de Android `app.kinsight`)

Fecha de entrada en vigor: 2026-10-14

Esta Política de privacidad explica qué información lee la aplicación Kinsight, adónde va, cómo se protege, cuánto tiempo se conserva y cómo se puede borrar. Kinsight es una aplicación de control parental: en el teléfono de un menor cuenta el tiempo de pantalla y lo comparte con los adultos del grupo familiar (padres, madres o tutores). Se publica en Google Play con el nombre de desarrollador que aparece en la ficha de la aplicación.

## 1. Responsable y contacto

Kinsight está desarrollada y publicada por Anatolii Kudriashov, desarrollador particular con domicilio en España («nosotros»).

Contacto para cualquier pregunta o solicitud sobre privacidad: kav2k13@gmail.com

## 2. Resumen

- Kinsight solo se conecta a internet para la sincronización a través de tu Yandex Disk, si la activaste; viene desactivada. Nosotros, el desarrollador, no tenemos servidores y no guardamos nada. La aplicación no tiene cuentas de Kinsight, publicidad, analíticas ni informes de errores, y no contiene código de terceros que envíe datos a ningún sitio.
- Nosotros, el desarrollador, nunca recibimos datos de la aplicación. No podemos ver el tiempo de pantalla de tu familia.
- En un teléfono configurado como teléfono de un menor, y solo después de que el menor lo acepte en ese teléfono, Kinsight envía estadísticas de tiempo de pantalla por Bluetooth a los teléfonos de los adultos del mismo grupo familiar y, si un adulto activó la sincronización con Yandex Disk, también a través del Yandex Disk de ese adulto, cifradas.
- El teléfono de un adulto no envía su propio tiempo de pantalla a nadie.
- Mientras el intercambio está activado, el teléfono del menor siempre muestra una notificación. El menor puede pausar o detener el intercambio en cualquier momento, y los adultos lo ven.

## 3. Qué información lee la aplicación

Kinsight solo lee lo que necesita para contar el tiempo de pantalla e intercambiarlo dentro del grupo familiar.

**En el teléfono de un menor (tras su consentimiento):**
- Los eventos de uso de aplicaciones que proporciona Android (qué aplicación estaba en primer plano y cuándo, cuándo se encendió y apagó la pantalla, cuándo se desbloqueó el teléfono). A partir de ellos, Kinsight calcula el tiempo de pantalla por día, por aplicación y por hora, y el número de desbloqueos. Kinsight no lee lo que se muestra dentro de las aplicaciones.
- La lista de aplicaciones que se pueden abrir desde la pantalla de inicio, con sus nombres, iconos y categorías de Android, para que los adultos sepan a qué aplicación corresponde el tiempo.
- Eventos sobre el estado del intercambio: una pausa y su fin, la sincronización detenida, el acceso a datos de uso desactivado, la notificación de Kinsight desactivada, la retirada del permiso «Dispositivos cercanos», la detención forzosa de la aplicación, un cambio en la hora del teléfono y los periodos de los que Android ya no proporciona datos de uso.
- La zona horaria del teléfono, para que el tiempo se cuente en la fecha local correcta.

**En todos los teléfonos del grupo (de menores y de adultos):**
- El nombre del teléfono o de la persona, introducido por el usuario (por ejemplo, «Teléfono de Ana»), y su función en el grupo (adulto o menor).
- Identificadores y claves criptográficas que la aplicación crea en ese teléfono: un identificador aleatorio del dispositivo, un identificador del grupo y claves públicas. No están vinculados a tu cuenta de Google, número de teléfono, IMEI, ID de publicidad ni a ningún otro identificador de hardware.
- Registros de pertenencia al grupo: quién se ha unido o ha salido, quién es administrador y breves señales de «sigo aquí» que indican al grupo cuándo estuvo un teléfono en contacto por última vez.
- Solo en los teléfonos de los adultos: las reglas de aviso de límites y los cambios de categoría de aplicaciones hechos por los adultos. Los cambios de categoría se comparten con los demás adultos; las reglas de límites se quedan en el teléfono donde se fijaron.
- Solo si un adulto activó la sincronización con Yandex Disk: el acceso a la carpeta de Kinsight en el Yandex Disk de ese adulto (un token de acceso), que los teléfonos del grupo reciben cifrado del teléfono de ese adulto, directamente o a través de los demás teléfonos del grupo, y, solo en el teléfono de ese adulto, el token que lo renueva. Kinsight nunca ve la contraseña de Yandex.

**Cámara:** cuando un teléfono se une a un grupo, Kinsight usa la cámara para escanear el código QR de la invitación. Las imágenes se procesan en el teléfono solo para leer el código, y no se guardan ni se envían a ningún sitio.

**Lo que Kinsight no lee ni recopila:** la ubicación, los mensajes, las llamadas, los contactos, las fotos y archivos, el historial de navegación, el contenido de la pantalla, las contraseñas, tu cuenta de Google, tu número de teléfono, el ID de publicidad y los identificadores de hardware.

## 4. Quién recibe la información

La información solo sale de un teléfono hacia los demás teléfonos del mismo grupo familiar que se emparejaron con él escaneando un código QR: por Bluetooth y, si un adulto activó la sincronización con Yandex Disk, como archivos cifrados en la carpeta de Kinsight del Yandex Disk de ese adulto.

- **El teléfono de un menor** envía su tiempo de pantalla, la lista de aplicaciones, los eventos, su nombre y sus claves a los teléfonos de todos los adultos del grupo, incluidos los que se unan más adelante. La lista actual de adultos se muestra en el teléfono del menor, en Ajustes, en la pantalla «Quién ve mis datos».
- **El teléfono de un adulto** guarda los datos de los menores y los pasa a los demás adultos del grupo, para que todos vean lo mismo. El teléfono de un adulto solo envía su propio nombre, sus claves, los registros de pertenencia y las señales de «sigo aquí»; nunca envía su propio tiempo de pantalla.
- **El teléfono de un menor no recibe** los datos de los demás teléfonos del grupo, salvo la lista de miembros del grupo.
- **Yandex** guarda los archivos cifrados en el Disco del adulto que activó la sincronización, en la propia cuenta de Yandex de ese adulto y según las condiciones entre ese adulto y Yandex. El contenido de los archivos está sellado con la clave del grupo, que Yandex no tiene. Yandex sí ve: al iniciar sesión, el nombre del teléfono con el que se inicia sesión y un identificador aleatorio de ese teléfono; en el Disco, los nombres de las carpetas y de los archivos (identificadores aleatorios y rangos de números), su tamaño y sus horas, y el identificador aleatorio del almacenamiento; y, como cada teléfono que sincroniza a través del Disco se conecta con Yandex, incluido el del menor, la dirección IP de cada uno de esos teléfonos.

Aparte de esos archivos cifrados, nadie más recibe la información: ni nosotros, ni Google, ni anunciantes, ni ninguna otra empresa. No vendemos, alquilamos ni cedemos datos a terceros, porque nunca los tenemos.

La aplicación se distribuye a través de tiendas de aplicaciones (RuStore, Google Play) y como archivo APK desde nuestro sitio web. La información que una tienda recopila al instalar y actualizar se rige por la política de privacidad de esa tienda, no por esta. Kinsight no envía ningún dato a Google.

## 5. Cómo se protege la información

- **Internet solo para el Disco.** Kinsight solo se conecta a internet para la sincronización a través del Yandex Disk de un adulto, si ese adulto la activó; viene desactivada. Entonces solo se conecta con Yandex (para iniciar sesión y con el Disco), y su acceso se limita a su propia carpeta en ese Disco, sin llegar a los demás archivos del adulto.
- **Cifrado en el Disco.** Los archivos del Disco están sellados con la clave del grupo, que solo tienen los teléfonos del grupo; la clave cambia cada vez que se elimina un teléfono del grupo. El acceso al Disco se guarda en el teléfono cifrado con una clave del Android Keystore y nunca se nos envía.
- **Transferencia cifrada.** Los teléfonos solo se comunican entre sí mediante una conexión Bluetooth cifrada (el protocolo Noise, patrón XX, con X25519, ChaCha20-Poly1305 y SHA-256). Antes de intercambiar datos, cada teléfono comprueba que el otro es miembro del grupo.
- **Datos firmados.** Cada registro lo firma el teléfono que lo creó (Ed25519), así que un teléfono que reenvía datos no puede modificarlos sin que se note.
- **Las claves se quedan en el teléfono.** Las claves privadas se crean en el teléfono y se guardan en el Android Keystore siempre que el teléfono lo admita; si no, se guardan cifradas con una clave del Android Keystore. Nosotros nunca tenemos estas claves.
- **Sin copias de seguridad.** Kinsight excluye sus datos de la copia de seguridad en la nube de Android y de la transferencia entre dispositivos.
- **En el teléfono,** los datos se guardan en el almacenamiento privado de la aplicación, protegido por el aislamiento de aplicaciones de Android y el cifrado del dispositivo.
- **Privacidad del Bluetooth.** Kinsight no emite un identificador fijo. Sus señales Bluetooth usan códigos cambiantes que solo los miembros del grupo pueden reconocer.

## 6. Permisos y para qué se necesitan

| Permiso | Para qué |
|---|---|
| Acceso a datos de uso (`PACKAGE_USAGE_STATS`) | En el teléfono de un menor, para contar cuánto tiempo se usa cada aplicación y cuántas veces se desbloquea el teléfono. Lo activas tú en los ajustes de Android y puedes desactivarlo en cualquier momento; en ese caso, los adultos ven que se desactivó el acceso a datos de uso. No se usa para nada más |
| Dispositivos cercanos (`BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`) | Para encontrar los teléfonos de la familia que están cerca e intercambiar datos con ellos. Kinsight declara que no usa el Bluetooth para determinar la ubicación, y no solicita el permiso de ubicación |
| Notificaciones (`POST_NOTIFICATIONS`) | Para mostrar la notificación permanente mientras el intercambio está activado (quién recibe los datos y un botón para detener la sincronización), y para avisar a los adultos de pausas, pérdidas de contacto, resúmenes y límites. Si se desactivan las notificaciones en el teléfono de un menor, los adultos lo ven, y el intercambio se pausa hasta que se vuelvan a activar; el tiempo contado mientras tanto se envía entonces |
| Servicio en primer plano, dispositivo conectado | Para mantener disponible el intercambio por Bluetooth en segundo plano mientras se muestra la notificación permanente |
| Cámara | Para escanear el código QR de la invitación al unirse a un grupo. Las imágenes no se guardan ni se envían |
| Inicio al arrancar | Para reanudar el recuento y el intercambio después de reiniciar el teléfono |
| Internet, ver conexiones de red (`INTERNET`, `ACCESS_NETWORK_STATE`) | Solo para la sincronización con Yandex Disk, si un adulto la activó: para iniciar sesión en Yandex, leer y escribir la carpeta de Kinsight en ese Disco y sincronizar solo cuando hay red. Mientras la sincronización está desactivada, Kinsight no se conecta a internet |

Algunas bibliotecas de Android que usa la aplicación añaden también el permiso «impedir que el teléfono entre en modo de suspensión». Kinsight solo lo usa para programar tareas en segundo plano en el teléfono.

## 7. Cuánto tiempo se conserva la información

Cada teléfono decide por sí mismo cuánto tiempo conserva el historial; el ajuste está en los ajustes de Kinsight y no se comparte con otros teléfonos. Por defecto:

- Historial detallado (tiempo por aplicación y por hora): **90 días**.
- Totales diarios: **hasta 1 año**.

Los registros más antiguos se borran automáticamente y ya no se aceptan de otros teléfonos. Los eventos de estado (pausas, pérdidas de contacto y similares) más antiguos que los totales diarios también se borran, salvo el último evento de cada tipo y el primer consentimiento. Los nombres e iconos de las aplicaciones se borran con el mismo calendario cuando ningún total diario conservado menciona la aplicación: en el teléfono que los describió, y en todos los teléfonos cuando ese teléfono ha salido del grupo. Los registros de pertenencia al grupo y las claves se conservan mientras el teléfono está en el grupo.

## 8. Cómo borrar la información

- **Salir del grupo** (pantalla Grupo, «Salir del grupo»): se borran los datos del grupo en este teléfono, y se avisa a los demás teléfonos de que este teléfono ha salido.
- **Desconectar este teléfono** (Ajustes, Avanzado, «Desconectar este teléfono y volver a emparejarlo»): se borra todo lo que hay en este teléfono, incluidos el historial, las claves y los ajustes.
- **Desinstalar la aplicación** o borrar su almacenamiento en los ajustes de Android: se borra todo lo que Kinsight guardó en este teléfono.
- **Un administrador puede eliminar un teléfono** del grupo; a partir de entonces el grupo ya no acepta datos nuevos de ese teléfono.
- **Archivos del Yandex Disk:** el adulto en cuyo Disco están puede borrar la carpeta de Kinsight de ese Disco en cualquier momento. Sin la clave del grupo los archivos no se pueden leer. Mientras la sincronización siga activada, los teléfonos del grupo vuelven a guardar sus datos en la carpeta; un teléfono que sale del grupo o se desconecta deja de hacerlo.

**Importante:** las copias que ya han llegado a otros teléfonos de la familia se guardan en esos teléfonos. Borrar los datos en un teléfono no elimina esas copias de inmediato; se borran según el ajuste de conservación de cada teléfono (por defecto, a los 90 días los detalles y al año los totales diarios), o de inmediato cuando ese teléfono sale del grupo, se restablece o se desinstala Kinsight en él. Como nunca recibimos los datos, no podemos borrarlos por ti, pero puedes escribirnos a kav2k13@gmail.com y te explicaremos cómo eliminarlos de cada teléfono.

## 9. Tus opciones y tu control

- **Primero, el consentimiento.** El teléfono de un menor no envía nada hasta que el menor toca «Aceptar» en la pantalla de consentimiento, que explica qué se envía y a quién. Si el menor elige «Ahora no», el teléfono se une al grupo sin compartir, y la familia ve que no comparte.
- **Pausa.** El intercambio se puede pausar con dos toques. El tiempo contado durante una pausa se envía cuando termina, y la propia pausa es visible para los adultos. Es intencionado: el intercambio solo se puede detener abiertamente, nunca a escondidas.
- **Notificación oculta.** En el teléfono de un menor, desactivar las notificaciones de Kinsight pausa el intercambio hasta que se vuelvan a activar. Los adultos ven que la notificación está oculta, y el tiempo contado mientras tanto se envía cuando vuelve a mostrarse.
- **Detener.** La sincronización se puede detener por completo con «Detener» en la notificación o en Ajustes; los adultos ven cuándo se detuvo.
- **Transparencia.** La pantalla «Quién ve mis datos» muestra quién recibe los datos, qué se envía y cuánto tiempo se conserva.
- **Permisos.** Cualquier permiso se puede retirar en los ajustes de Android en cualquier momento.

## 10. Menores

Kinsight está pensada para padres, madres y tutores, y en las tiendas de aplicaciones está dirigida a adultos. Un adulto instala Kinsight en el teléfono del menor y lo empareja con el grupo familiar. Antes de que se comparta ningún dato, el menor ve en ese teléfono una pantalla de consentimiento redactada en lenguaje sencillo («Los adultos de tu familia verán cuánto tiempo pasas con el teléfono y en qué aplicaciones») y tiene que aceptarla expresamente. El menor puede ver en cualquier momento quién recibe los datos y puede pausar o detener el intercambio; los adultos ven la pausa o la detención.

Solo comparten tiempo de pantalla los teléfonos configurados como teléfono de un menor. Kinsight no debe usarse para vigilar a un adulto, ni siquiera con su consentimiento.

No recopilamos datos de menores ni de nadie más: los datos del menor solo van a los teléfonos de los adultos del grupo familiar y, si un adulto activó la sincronización con Yandex Disk, pasan cifrados por el Disco de ese adulto. Kinsight no muestra anuncios, no tiene compras dentro de la aplicación y no usa el ID de publicidad, la ubicación ni identificadores de hardware.

El adulto que instala Kinsight en el teléfono de un menor es responsable de hacerlo de forma lícita y de implicar al menor de la manera adecuada a su edad.

## 11. Avisos de las tiendas y del sistema

Como Kinsight comparte el tiempo de pantalla de un menor con los adultos, está declarada en Google Play como aplicación de supervisión de menores y descrita en RuStore como aplicación de control parental. Por eso, Google Play Protect u otra comprobación del teléfono puede mostrar en el teléfono un mensaje de que hay instalada una aplicación de supervisión o de control parental. Es lo esperado: el mensaje está ahí para que todas las personas que usan el teléfono sepan que Kinsight está instalada. No significa que la aplicación tenga ningún problema.

## 12. Tus derechos

Tienes los derechos que te reconoce el Reglamento General de Protección de Datos de la UE (RGPD), entre ellos los derechos de acceso, rectificación, supresión, limitación, oposición y portabilidad. Los datos que crea Kinsight se quedan en los teléfonos de tu familia y, cifrados, en el Yandex Disk de un adulto si la sincronización a través de él está activada, y ejerces estos derechos directamente en la aplicación (consulta las secciones 8 y 9). No tenemos ninguno de tus datos, así que por nuestra parte no hay nada a lo que acceder, que corregir o que borrar. Puedes escribirnos a kav2k13@gmail.com con cualquier pregunta, y puedes presentar una reclamación ante una autoridad de protección de datos; en España es la Agencia Española de Protección de Datos (www.aepd.es).

Si nos escribes por correo electrónico, usamos tu dirección y tu mensaje solo para responderte, y borramos la correspondencia cuando ya no la necesitamos para ello.

## 13. Cambios en esta política

Si cambiamos esta política, publicaremos la nueva versión en esta dirección con una nueva fecha de entrada en vigor. Si un cambio afecta a qué datos envía la aplicación o a quién, se actualizará la pantalla de consentimiento de la aplicación, y no se enviará nada nuevo antes de que el menor lo acepte.

## 14. Contacto

Anatolii Kudriashov, España
Correo electrónico: kav2k13@gmail.com
