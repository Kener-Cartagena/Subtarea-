# SubTareas — version Java (JavaFX)

Reescritura de la app Python/tkinter con JavaFX. Misma base de datos
(`~/SubTareas/data.db`): conservas todos tus tickets, fases y tareas.

## Requisitos
- JDK 21 o superior
- Maven 3.9+

## Ejecutar
    mvn javafx:run

## Generar un .exe / .msi (sin necesidad de que el usuario tenga Java)
1) Compila el runtime:   mvn javafx:jlink
2) Con jpackage:
   jpackage --type exe --name SubTareas --input target --main-jar ... (o usa el runtime de jlink)
   Opcion mas simple: usa `mvn javafx:jlink` y distribuye la carpeta
   `target/subtareas/bin/launcher` (Windows: `subtareas.exe`).
