# PlayerWorlds

NeoForge-Mod für Minecraft 1.21.1 (z.B. All The Mods 10): Jeder Spieler bekommt eine eigene Welt.

## Befehle

| Befehl | Beschreibung |
|---|---|
| `/createworld` | Erstellt deine eigene Welt (Vanilla-Overworld-Generierung, eigener Seed) und teleportiert dich hinein |
| `/createworld <name>` | Erstellt deine Welt mit einem Namen (z.B. `/createworld Meine Insel`) |
| `/createworld flat [name]` | Erstellt stattdessen eine Flachwelt |
| `/createworld void [name]` | Leere Void-Welt mit 5x5-Startplattform |
| `/renameworld <name>` | Benennt deine Welt um |
| `/tpworld` | Teleportiert dich in deine Welt |
| `/deleteworld` | Fragt nach Bestätigung zum Löschen |
| `/deleteworld confirm` | Löscht deine Welt endgültig (innerhalb von 30 Sekunden) |
| `/worldinvite <spieler>` | Gibt einem Spieler Zugang zu deiner Welt |
| `/worldkick <spieler>` | Entzieht den Zugang (Spieler wird ggf. rausteleportiert) |
| `/worldmembers` | Zeigt Status und eingeladene Spieler |
| `/lockworld` | Sperrt deine Welt: nur du kommst rein (Gäste werden rausteleportiert) |
| `/unlockworld` | Normalzustand (Standard): du und Eingeladene kommen rein |
| `/openworld` | Öffnet deine Welt für alle |
| `/visitworld <name oder spieler>` | Teleportiert dich in eine andere Welt (per Weltname oder Besitzer) |

Jeder Spieler kann nur eine Welt gleichzeitig haben. Welten bleiben nach einem Server-Neustart erhalten
(`world/dimensions/playerworlds/<uuid>`), beim Löschen wird der Ordner entfernt.

## Installation

Die Jar aus `build/libs/` in den `mods`-Ordner des Servers legen. Clients ohne die Mod können weiterhin joinen.

## Bauen

```
./gradlew build
```

Benötigt Java 21.
