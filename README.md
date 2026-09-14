# PlayerWorlds

NeoForge-Mod für Minecraft 1.21.1 (z.B. All The Mods 10): Jeder Spieler bekommt eine eigene Welt.

## Befehle

| Befehl | Beschreibung |
|---|---|
| `/createworld` | Erstellt deine eigene Welt (normale Overworld-Generierung, eigener Seed) und teleportiert dich hinein |
| `/createworld flat` | Erstellt stattdessen eine Flachwelt |
| `/myworld` | Teleportiert dich in deine Welt |
| `/deleteworld` | Fragt nach Bestätigung zum Löschen |
| `/deleteworld confirm` | Löscht deine Welt endgültig (innerhalb von 30 Sekunden) |

Jeder Spieler kann nur eine Welt gleichzeitig haben. Welten bleiben nach einem Server-Neustart erhalten
(`world/dimensions/playerworlds/<uuid>`), beim Löschen wird der Ordner entfernt.

## Installation

Die Jar aus `build/libs/` in den `mods`-Ordner des Servers legen. Clients ohne die Mod können weiterhin joinen.

## Bauen

```
./gradlew build
```

Benötigt Java 21.
