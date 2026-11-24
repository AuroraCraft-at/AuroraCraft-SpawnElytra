<div align="center">

# ✦ AuroraCraft Spawn Elytra

[![Build](https://github.com/AuroraCraft-at/SpawnElytra/actions/workflows/build.yml/badge.svg)](https://github.com/AuroraCraft-at/SpawnElytra/actions/workflows/build.yml)
![Paper 1.21+](https://img.shields.io/badge/Paper-1.21%2B-blue)
![Folia](https://img.shields.io/badge/Folia-supported-green)
![License MPL-2.0](https://img.shields.io/badge/License-MPL--2.0-orange)

_Im Spawn fliegen wie mit einer Elytra, ohne eine zu tragen. Mit F boostest du dich los._<br>
_Beliebig viele Spawns, in jeder Welt: Overworld, Nether, End._

</div>

AuroraCraft Spawn Elytra ist der Fork von [SpawnElytra von blaxk](https://github.com/blax-k/SpawnElytra)
für den AuroraCraft-Server, erweitert um mehrere Spawns pro Welt, ein Raketen-Limit
und Unterstützung für die eigene Elytra der Spieler.

## Neu in 2.0 (gegenüber dem Original)

- **Mehrere Spawns, in jeder Welt.** Jeder Spawn hat einen Namen, eine Welt und eine Form
  (Kreis oder Rechteck). Ein Spawn kann jede Einstellung aus `defaults` für sich überschreiben,
  zum Beispiel einen eigenen Aktivierungsmodus im Nether.
- **Spawns dürfen sich nicht überlappen.** Wer einen Spawn in einen anderen hinein setzt,
  bekommt eine Fehlermeldung und es wird nichts gespeichert.
- **Spawns sind Säulen.** Kreis und Rechteck gelten über die ganze Welthöhe, egal wie hoch man fliegt.
  (Früher war der Kreis eine Kugel um den Spawnpunkt.)
- **Raketen nach dem Losboosten.** Raketen funktionieren während des ganzen Flugs, auch nachdem man
  den Spawn verlassen hat, bis zur Landung. Mit `fireworks.max_rockets` legst du fest, wie viele:
  `-1` = unbegrenzt, `0` = keine, `10` = höchstens 10 pro Flug.
- **Eigene Elytra.** Wer schon eine Elytra trägt, fliegt mit seiner eigenen (normales Vanilla-Verhalten,
  Haltbarkeit inklusive) und bekommt im Spawn trotzdem die Vorteile: Boost mit F und kein Fallschaden.
  Raketen sind dann nie begrenzt. Bedrock-Spieler mit eigener Elytra behalten sie.
- **Kein Auto-Updater mehr.** Der Updater des Originals hätte diesen Fork mit der Original-Version
  überschrieben. Er ist komplett entfernt, ebenso bStats.
- **Reload-Fehler behoben.** Im Original liefen nach jedem `/se reload` die alten Listener mit der alten
  Config weiter. Jetzt gibt es einen einzigen Flug-Controller für alle Spawns.

## Funktionen

- **Elytra-Flug im Spawn** ohne Elytra-Item, mit vier Aktivierungsarten:
  - `double_jump`: zweimal Leertaste
  - `auto`: automatisch, sobald unter dem Spieler Luft ist
  - `sneak_jump`: schleichen und springen
  - `f_key`: F drücken (Hand tauschen), katapultiert den Spieler nach oben
- **Boost** mit F: Stärke, Richtung (vorwärts/hoch), Anzahl pro Flug, Cooldown und Sound einstellbar.
- **Kein Fallschaden** während des Flugs und kurz nach der Landung.
- **Hunger** optional: pro Aktivierung, pro Strecke oder pro Zeit, mit Untergrenze.
- **Spielmodi**: in Creative/Adventure abschaltbar.
- **Bedrock (Geyser/Floodgate)**: Bedrock-Spieler bekommen im Spawn eine geschützte Temp-Elytra,
  ihre Brustplatte wird sicher zurückgegeben. Geboostet wird mit Schleichen.
- **Setup-Hilfe** im Spiel mit Partikel-Vorschau, **Partikel-Anzeige** aller Spawns.
- **PlaceholderAPI**, **5 Sprachen** (en, de, es, fr, pl), **Folia**-kompatibel.

## Installation

1. `auroracraft-spawnelytra-2.0.0.jar` in den `plugins`-Ordner legen.
2. Server starten. Fertig: Es gibt einen Spawn `world` mit 100 Blöcken Radius um den Weltspawn.

### Umstieg vom Original-Plugin „SpawnElytra“

1. **Die alte `SpawnElytra`-Jar entfernen.** Laufen beide gleichzeitig, schaltet sich dieses Plugin
   ab und schreibt einen Hinweis ins Log.
2. Beim ersten Start werden `config.yml` und die Spielerstatistiken aus `plugins/SpawnElytra`
   nach `plugins/AuroraCraftSpawnElytra` kopiert. Der alte Ordner bleibt unverändert.
3. Die alte Config (1.2 bis 1.5) wird automatisch umgewandelt: **jede Welt wird zu einem Spawn**,
   alle Einstellungen bleiben erhalten. `fireworks.disable_in_spawn_elytra: true` wird zu
   `max_rockets: 0`. Die vorherige Datei liegt danach in `backups/`.
4. Rechte (`spawnelytra.*`), Befehle (`/spawnelytra`, `/se`) und Platzhalter (`%spawnelytra_…%`)
   heißen gleich wie vorher.

## Spawns anlegen

Runder Spawn um die eigene Position:

```
/se spawn create hub circle 80
```

Rechteckiger Spawn mit der Setup-Hilfe (zwei Ecken ablaufen):

```
/se setup netherhub
/se set pos1
/se set pos2
/se setup save
```

Einstellungen eines einzelnen Spawns ändern, `default` setzt wieder auf den Standardwert:

```
/se spawn set netherhub activation_mode f_key
/se spawn set netherhub fireworks.max_rockets 10
/se spawn set hub radius 120
/se spawn set hub boost.strength default
```

Prüfen und verwalten:

```
/se spawn list
/se spawn info hub
/se visualize hub
/se visualize all
/se spawn tp netherhub
/se spawn delete hub confirm
```

Liegt eine Position oder die neue Fläche in einem anderen Spawn, kommt eine Fehlermeldung
mit dem Namen des anderen Spawns, und es wird nichts gespeichert. Das gilt auch, wenn ein
größerer Radius in einen Nachbar-Spawn reichen würde.

## Konfiguration

Die `config.yml` hat drei Teile:

- **globale Einstellungen**: Sprache, Spielmodi, Bedrock, Nachrichten, Partikel, Hunger
- **`defaults`**: Flug-Einstellungen, die für alle Spawns gelten
- **`spawns`**: die Spawns selbst, jeweils mit optionalen eigenen Werten

```yaml
defaults:
  activation_mode: double_jump
  boost:
    enabled: true
    strength: 4
    direction: forward      # forward oder upward
    max_boosts: 1           # Boosts pro Flug
    boost_cooldown: 0       # Sekunden zwischen Boosts
    sound: ENTITY_BAT_TAKEOFF
  f_key:
    launch_strength: 1.5
  fireworks:
    max_rockets: -1         # -1 = unbegrenzt, 0 = keine, z.B. 10 = max. 10 pro Flug

spawns:
  world:                    # Name des Spawns
    world: world
    enabled: true
    shape: circle
    radius: 100             # ohne 'center' zählt der Weltspawn als Mitte
  netherhub:
    world: world_nether
    enabled: true
    shape: rectangle
    pos1: {x: -40, z: -40}  # zwei gegenüberliegende Ecken (Blockkoordinaten)
    pos2: {x: 40, z: 25}
    activation_mode: f_key  # eigener Wert nur für diesen Spawn
    fireworks:
      max_rockets: 10
```

Spawns in Welten, die beim Start noch nicht geladen sind, werden aktiv, sobald die Welt geladen ist.
Ausnahme: Ein Kreis ohne `center` braucht die Welt schon beim Start, weil der Weltspawn die Mitte ist.
Multiverse-Core wird vor diesem Plugin geladen, damit seine Welten bekannt sind.

<details>
<summary>Alle Schlüssel, die ein Spawn überschreiben kann</summary>

| Schlüssel | Werte |
|---|---|
| `activation_mode` | `double_jump`, `auto`, `sneak_jump`, `f_key` |
| `boost.enabled` | `true` / `false` |
| `boost.strength` | Zahl > 0 |
| `boost.direction` | `forward`, `upward` |
| `boost.max_boosts` | ganze Zahl ≥ 1 |
| `boost.boost_cooldown` | Sekunden ≥ 0 |
| `boost.sound` | Name aus der [Sound-Liste](https://jd.papermc.io/paper/1.21/org/bukkit/Sound.html) |
| `f_key.launch_strength` | Zahl > 0 |
| `fireworks.max_rockets` | `-1` (unbegrenzt), `0`, oder eine Anzahl |

Zusätzlich per `/se spawn set`: `enabled` und `radius` (nur Kreise).

</details>

## Befehle und Rechte

Alle Befehle gehen mit `/spawnelytra` oder kurz `/se`.

| Befehl | Beschreibung |
|---|---|
| `/se info` | Version, Sprache, Anzahl Spawns |
| `/se spawn list` | Alle Spawns |
| `/se spawn info <name>` | Form, Status und Einstellungen eines Spawns |
| `/se spawn create <name> circle <radius>` | Runder Spawn um die eigene Position |
| `/se spawn create <name> rectangle` | Startet die Setup-Hilfe für ein Rechteck |
| `/se setup <name>` | Setup-Hilfe für einen neuen Spawn, oder bestehenden neu zeichnen |
| `/se set pos1` / `pos2` | Ecken in der Setup-Hilfe setzen |
| `/se setup save` / `exit` | Setup speichern oder abbrechen |
| `/se spawn set <name> <setting> <wert\|default>` | Einstellung eines Spawns ändern |
| `/se spawn tp <name>` | Zum Spawn teleportieren |
| `/se spawn delete <name> confirm` | Spawn löschen |
| `/se visualize [name\|all\|stop] [sekunden]` | Spawn-Grenzen mit Partikeln anzeigen |
| `/se settings` | Sprache und Nachrichtenstil |
| `/se reload` | Config neu laden (laufende Flüge enden) |

| Recht | Standard | Wofür |
|---|---|---|
| `spawnelytra.use` | alle | Spawn-Elytra benutzen |
| `spawnelytra.useboost` | alle | Boost benutzen |
| `spawnelytra.admin` | OP | Alle Befehle |

## Platzhalter (PlaceholderAPI)

| Platzhalter | Inhalt |
|---|---|
| `%spawnelytra_fly_count%` | Anzahl Flüge des Spielers |
| `%spawnelytra_boost_count%` | Anzahl Boosts |
| `%spawnelytra_total_count%` | Flüge + Boosts |
| `%spawnelytra_flying%` | `true` während eines Spawn-Elytra-Flugs |
| `%spawnelytra_in_area%` | `true` in einem aktiven Spawn |
| `%spawnelytra_spawn%` | Name des Spawns (des Flugs oder an der Position) |
| `%spawnelytra_boosts_remaining%` | übrige Boosts in diesem Flug |
| `%spawnelytra_rockets_remaining%` | übrige Raketen in diesem Flug, `∞` bei unbegrenzt |

## Bedrock (Geyser/Floodgate)

Bedrock-Clients können ohne echte Elytra nicht gleiten. Bedrock-Spieler bekommen deshalb im Spawn
eine geschützte Temp-Elytra (springen, dann im Fallen nochmal springen). Ihre Brustplatte wird im
Spielerdatensatz gesichert und beim Verlassen, Landen, Ausloggen, Tod oder nach einem Absturz
zurückgegeben. Die Temp-Elytra lässt sich nicht verschieben, droppen oder duplizieren, andere
Spieler sehen weiter die echte Brustplatte. Geboostet wird mit **Schleichen**.
Bedrock-Spieler, die schon eine eigene Elytra tragen, behalten diese.

## Selbst bauen

Java 21 wird benötigt.

```bash
./gradlew build
```

Die Jar liegt danach unter `build/libs/auroracraft-spawnelytra-2.0.0.jar`, die Tests laufen dabei mit.

## Lizenz

[Mozilla Public License 2.0](LICENSE). Basiert auf [SpawnElytra](https://github.com/blax-k/SpawnElytra)
von blaxk. Geänderte Dateien bleiben unter der MPL 2.0.

Probleme bitte als [Issue](https://github.com/AuroraCraft-at/SpawnElytra/issues) melden.
