# Multiplayer-Mapping

Server-Mapping für die Mehrspieler-Liste des [Lumora Clients](https://github.com/LumoraClient/lumora-launcher):
schöner Name, Kategorien, Icon, Hintergrundbild und Spielmodi bekannter Minecraft-Server, in einer Datei.
Der Client lädt die Daten nur noch von hier.

## Inhalt

- `servers.json`: alle Server
- `servers/<id>/icon.png` (128×128) und `servers/<id>/background.png` (640×360): die Bilder, auf die Größe
  verkleinert, die der Client anzeigt

```json
{
  "format": 1,
  "servers": {
    "hypixel": {
      "name": "Hypixel Network",
      "addresses": ["mc.hypixel.net", "hypixel.net"],
      "categories": ["MINIGAMES", "PVP"],
      "icon": { "file": "servers/hypixel/icon.png", "sha": "<git blob sha1>", "source": "<blob sha1 der Quelle>" },
      "background": { "file": "servers/hypixel/background.png", "sha": "…", "source": "…" },
      "gamemodes": [{ "name": "SkyBlock", "color": "#RRGGBB", "versions": "1.8<*" }],
      "from": { "norisk": "hypixel", "labymod": "hypixel" }
    }
  }
}
```

- `addresses`: ein Server passt, wenn seine Adresse gleich einer davon ist oder eine Subdomain davon
  (`play.example.net` passt zu `example.net`).
- `sha`: Git-Blob-SHA-1 der Bilddatei; der Client prüft jedes Bild damit.
- `color` und `versions` sind optional. `versions` hat die Form `von<bis`, mehrere mit Komma, `*` für offen.

## Quellen

[`tools/BuildMapping.java`](tools/BuildMapping.java) baut das Mapping aus zwei öffentlichen Repositories:

| Quelle | Was übernommen wird |
| --- | --- |
| [NoRiskClient/nrc-server-mappings](https://github.com/NoRiskClient/nrc-server-mappings) (GPL-3.0) | Name, Adressen, Kategorien, Spielmodi, Icon und Hintergrund (verkleinert) |
| [LabyMod/server-media](https://github.com/LabyMod/server-media) | nur Fakten: Name, direkte Adresse, Wildcard-Domains, Spielmodi (Name, Farbe, Versionen); keine Bilder |

Server aus beiden Quellen werden über ihre Adressen zusammengeführt. Haben beide Spielmodi, gelten die von
LabyMod (mit Farben und Versionen). Jede heruntergeladene Datei wird gegen ihren Git-Blob-Hash aus der Quelle
geprüft.

Ein GitHub-Actions-Workflow ([`update.yml`](.github/workflows/update.yml)) baut das Mapping alle sechs Stunden neu
und committet nur, wenn sich etwas geändert hat. Lokal:

```
java --source 21 tools/BuildMapping.java .
```

## Lizenz und Hinweise

Dieses Repository steht unter der [GPL-3.0](LICENSE), wie die NoRisk-Daten, auf denen es aufbaut. Die Bilder
wurden gegenüber der Quelle verkleinert.

LabyMod/server-media nennt keine Lizenz. Von dort werden nur Fakten übernommen (Namen, Adressen, Spielmodi) und
keine Bilder oder Texte. Diese Fakten fallen nicht unter die GPL dieses Repositories.

Alle Servernamen, Logos und Marken gehören ihren jeweiligen Inhabern und dienen nur zur Erkennung des Servers.
Server-Betreiber, die ihren Eintrag ändern oder entfernen lassen möchten, öffnen bitte ein Issue; Änderungen in
den Quell-Repositories werden automatisch übernommen.
