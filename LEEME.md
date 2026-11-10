# Ritmógrafo para Android

Esta app muestra el ritmo de tu música en gráficas: el pulso, los golpes, los graves, el espectro y los BPM.
Funciona con canciones guardadas en el teléfono **y con el micrófono**, así que puedes poner música en una bocina y la app la va a "escuchar".

## Opción A: sacar el APK con GitHub (sin instalar nada)

1. Crea una cuenta gratis en https://github.com y crea un repositorio nuevo.
2. Sube todo el contenido de esta carpeta al repositorio. Puedes usar el botón "Add file › Upload files" y arrastrar todo, **incluida la carpeta `.github`**.
3. Entra a la pestaña **Actions**. La compilación "Construir APK" arranca sola y tarda unos 3 a 5 minutos.
4. Cuando termine con ✅, abre esa ejecución y descarga **Ritmografo-APK** (viene como .zip y adentro está `app-debug.apk`).

## Opción B: Android Studio

1. Abre esta carpeta en Android Studio y espera a que termine de sincronizar.
2. Ve al menú **Build › Build App Bundle(s) / APK(s) › Build APK(s)**.
3. El APK queda en `app/build/outputs/apk/debug/app-debug.apk`.

## Instalar en el celular

1. Pasa `app-debug.apk` al teléfono. Puede ser por cable, por Google Drive o mandándotelo por WhatsApp o correo.
2. Ábrelo. Android va a pedir permiso para "instalar apps de origen desconocido": acéptalo solo para esa app (Archivos, Drive, etc.).
3. Si sale el aviso de Play Protect, toca **Más detalles › Instalar de todas formas**. El aviso aparece porque la app no viene de Play Store.
4. La primera vez que toques 🎙 Micrófono, la app te va a pedir permiso. Acéptalo.

Requiere Android 8.0 o más reciente.
