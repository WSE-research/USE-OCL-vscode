# USE (UML/OCL) für Visual Studio Code

Diese Erweiterung hebt USE-Modelle (`.use`) und Soil-Skripte (`.soil`) farbig hervor und prüft sie schon beim Tippen. Geprüft wird mit dem Compiler von USE 7.5 selbst: Fehler und Warnungen erscheinen als Unterstreichung im Editor und in der Ansicht „Probleme“, mit denselben Meldungen und Positionen wie bei `use -c`.

## Was geprüft wird

- **Modelle (`.use`):** alles, was `use -c` prüft, also Syntax, Namen von Klassen, Attributen, Rollen und Operationen, Typen in OCL-Ausdrücken. Warnungen der Typprüfung, etwa dass `Set{0, 1, 2}->includes('2')` immer `false` ergibt, stehen an der betroffenen Stelle; USE selbst nennt dafür keine Zeile.
- **Soil-Skripte (`.soil`):** jede Zeile so, wie die USE-Shell sie liest. Gemeldet werden Syntaxfehler in Anweisungen nach `!` und in Ausdrücken nach `?`, `??` und `:`, unbekannte Befehle (meist ein vergessenes `!`) sowie `=` statt `:=` bei einer Zuweisung.
- **Nicht geprüft** wird in Soil-Skripten, ob Klassen, Attribute und Objekte existieren, denn Objekte entstehen oft in einem anderen Skript. Dafür das Skript in USE ausführen, in den SE-Übungen etwa mit `./run-use.sh -nogui modell.use skript.soil`.

## Voraussetzungen

Docker oder Podman und das Image `use-ocl:7.5.0` mit USE unter `/opt/use`. Fehlt das Image, bietet die Erweiterung an, es zu bauen (Befehl „USE: Container-Image bauen“, lädt einmalig USE von GitHub); von Hand geht das mit `docker build -t use-ocl:7.5.0 container`. In den SE-Übungen baut `run-use.sh` dasselbe Image; wer es dort schon aufgerufen hat, hat das Image bereits.

Ohne Container geht es mit Java 21 und einer entpackten USE-7.5-Installation. Dann in den Einstellungen `useOcl.useHome` auf den USE-Ordner setzen und, falls `java` im PATH älter als 21 ist, `useOcl.javaHome` auf ein Java 21.

Die farbige Hervorhebung funktioniert auch ohne beides; dann fehlt nur die Prüfung.

## Installation

In VS Code unter „Erweiterungen“ im Menü „…“ den Punkt „Aus VSIX installieren…“ wählen und `use-ocl-0.1.0.vsix` angeben (entsteht mit `./build.sh`, siehe unten), oder im Terminal:

```
code --install-extension use-ocl-0.1.0.vsix
```

Danach einen Ordner mit `.use`- und `.soil`-Dateien öffnen. Beim ersten Öffnen einer solchen Datei startet der Sprachserver (im Container etwa eine Sekunde).

## Einstellungen

| Einstellung | Standard | Bedeutung |
|---|---|---|
| `useOcl.server.mode` | `auto` | `container`, `java` oder `auto` (lokales Java, wenn `useOcl.useHome` gesetzt ist und Java 21 vorhanden, sonst Container) |
| `useOcl.container.engine` | `auto` | `docker`, `podman` oder `auto` (das erste, das läuft; zuerst Docker, wie `run-use.sh`) |
| `useOcl.container.image` | `use-ocl:7.5.0` | Image mit USE unter `/opt/use` |
| `useOcl.useHome` | leer | USE-Installation für den Betrieb ohne Container (leer: Umgebungsvariable `USE_HOME`) |
| `useOcl.javaHome` | leer | Java 21 für den Betrieb ohne Container (leer: `JAVA_HOME` bzw. `java` im PATH) |
| `useOcl.trace.server` | `off` | Nachrichtenverkehr mit dem Server im Ausgabekanal „USE“ protokollieren |

Ist der Standardspeicher von Podman nicht nutzbar, nimmt die Erweiterung wie `run-use.sh` den eigenen Speicher unter `/var/tmp/use-ocl-<uid>` (änderbar mit `USE_PODMAN_STORE`). Der Container läuft ohne Netz und bekommt nur den Code des Sprachservers eingebunden; den Text der Dateien erhält er über die Verbindung zu VS Code.

## Befehle

- **USE: Sprachserver neu starten**, etwa nach dem Bauen des Images von Hand.
- **USE: Container-Image bauen** aus `container/Dockerfile` (dasselbe Image wie `run-use.sh` in den SE-Übungen).

Meldungen beim Start und Fehler des Servers stehen im Ausgabekanal „USE“ (Ansicht „Ausgabe“).

## Bauen und testen

`./build.sh` baut den Sprachserver und das Paket `use-ocl-<version>.vsix`; `./build.sh test` testet zusätzlich. Benötigt werden ein JDK 21 (`JAVA_HOME=~/jdk21 ./build.sh`), Maven und Node.js. Die USE-Bibliotheken holt das Skript aus dem Image `use-ocl:7.5.0` nach `.build/use` (oder aus `USE_HOME`).

Die Tests prüfen die Grammatiken (`test/grammar`, mit vscode-tmgrammar-test), den Sprachserver über LSP mit lokalem Java und im Container (`test/server-test.js` mit den Beispielen in `test/samples`) und die Erweiterung in VS Code selbst, unsichtbar mit `xvfb-run` (`test/e2e`).

Aufbau:

- `extension.js` startet den Sprachserver (lokales Java oder Container) und verbindet ihn mit VS Code.
- `syntaxes/` enthält die Grammatiken für die Hervorhebung; die Schlüsselwörter folgen den Grammatiken von USE 7.5 (`grammars/base/*.gpart` in `use-core-7.5.0.jar`).
- `java/` enthält den Sprachserver (LSP4J). Er ruft `USECompiler` für Modelle und die Parser der USE-Shell für Soil-Zeilen auf und macht aus deren Ausgabe Diagnosen. `de.htwk.uselsp.Check` prüft Dateien auf der Kommandozeile genauso.

Der Server nutzt interne Klassen von USE und ist deshalb an USE 7.5.0 gebunden; nach einem Wechsel der USE-Version `./build.sh test` laufen lassen.

## Lizenz

Die Erweiterung steht unter der MIT-Lizenz (siehe `LICENSE`). USE selbst (Universität Bremen, GPL Version 2 oder später) gehört nicht dazu, es kommt aus dem Container-Image oder der eigenen Installation. Im Paket liegen LSP4J (EPL-2.0 oder BSD-3-Clause) und Gson (Apache-2.0) bei.
