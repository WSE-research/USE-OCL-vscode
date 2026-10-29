# USE (UML-based Specification Environment, Universität Bremen) in einem Container.
# Gebaut von der Erweiterung (Befehl „USE: Container-Image bauen“) oder mit
# docker build -t use-ocl:7.5.0 container; dasselbe Image wie run-use.sh in den
# SE-Übungen. USE 7.5 benötigt Java 21.
# voll qualifiziert, damit Podman ohne Suchliste für Kurznamen auskommt
FROM docker.io/library/eclipse-temurin:21-jre-noble

ARG USE_VERSION=7.5.0
ARG USE_SHA256=9b5a49d1d2345e65d1da8518008887e8bc12df388b6eef08228c5a1052618d10

# X11-Bibliotheken und Schriften für die grafische Oberfläche (Swing).
# apt lädt ohne Benutzerwechsel nach _apt: Rootless Podman ohne subuid-Bereiche
# kennt im Container nur root, der Wechsel scheitert dort.
RUN echo 'APT::Sandbox::User "root";' > /etc/apt/apt.conf.d/99-ohne-sandbox \
 && apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl unzip \
      libxext6 libxrender1 libxtst6 libxi6 fontconfig fonts-dejavu-core \
 && rm -rf /var/lib/apt/lists/*

# Offizielles Release von GitHub, gegen die Prüfsumme geprüft. Ein hängender
# Download bricht nach einer Minute unter 1 kB/s ab und wird wiederholt.
RUN curl -fsSL --connect-timeout 30 --speed-limit 1000 --speed-time 60 \
      --retry 3 --retry-delay 5 --retry-all-errors -o /tmp/use.zip \
      "https://github.com/useocl/use/releases/download/v${USE_VERSION}/use-${USE_VERSION}-use-bin.zip" \
 && echo "${USE_SHA256}  /tmp/use.zip" | sha256sum -c - \
 && unzip -q /tmp/use.zip -d /opt \
 && mv "/opt/use-${USE_VERSION}" /opt/use \
 && rm /tmp/use.zip

ENV LD_LIBRARY_PATH=/opt/use/lib
WORKDIR /work
# -nr: USE bringt für Linux keine GNU-Readline-Bibliothek mit, ohne den Schalter warnt es bei jedem Start
ENTRYPOINT ["java", "-Dawt.useSystemAAFontSettings=on", "-jar", "/opt/use/lib/use-gui.jar", "-nr"]
