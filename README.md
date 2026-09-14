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
| `/worldinvite <spieler>` | Gibt einem Spieler Zugang zu deiner Welt |
| `/worldkick <spieler>` | Entzieht den Zugang (Spieler wird ggf. rausteleportiert) |
| `/worldmembers` | Zeigt Status und eingeladene Spieler |
| `/lockworld` | Sperrt deine Welt: nur du und Eingeladene kommen rein (Standard) |
| `/unlockworld` | Öffnet deine Welt für alle |
| `/visitworld <spieler>` | Teleportiert dich in die Welt eines anderen Spielers |

Jeder Spieler kann nur eine Welt gleichzeitig haben. Welten bleiben nach einem Server-Neustart erhalten
(`world/dimensions/playerworlds/<uuid>`), beim Löschen wird der Ordner entfernt.

## Installation

Die Jar aus `build/libs/` in den `mods`-Ordner des Servers legen. Clients ohne die Mod können weiterhin joinen.

## Bauen

```
./gradlew build
```

Benötigt Java 21.
