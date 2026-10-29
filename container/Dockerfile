# USE (UML-based Specification Environment, University of Bremen) in a container.
# Built by the extension (command "USE: Container-Image bauen") or with
# docker build -t use-ocl:7.5.0 container; the same image as run-use.sh in the
# SE exercises. USE 7.5 requires Java 21.
# fully qualified so that Podman does not need a search list for short names
FROM docker.io/library/eclipse-temurin:21-jre-noble

ARG USE_VERSION=7.5.0
ARG USE_SHA256=9b5a49d1d2345e65d1da8518008887e8bc12df388b6eef08228c5a1052618d10

# X11 libraries and fonts for the graphical user interface (Swing).
# apt downloads without switching to the user _apt: rootless Podman without subuid
# ranges knows only root inside the container; the switch fails there.
RUN echo 'APT::Sandbox::User "root";' > /etc/apt/apt.conf.d/99-ohne-sandbox \
 && apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl unzip \
      libxext6 libxrender1 libxtst6 libxi6 fontconfig fonts-dejavu-core \
 && rm -rf /var/lib/apt/lists/*

# Official release from GitHub, verified against the checksum. A stalled
# download aborts after one minute below 1 kB/s and is retried.
RUN curl -fsSL --connect-timeout 30 --speed-limit 1000 --speed-time 60 \
      --retry 3 --retry-delay 5 --retry-all-errors -o /tmp/use.zip \
      "https://github.com/useocl/use/releases/download/v${USE_VERSION}/use-${USE_VERSION}-use-bin.zip" \
 && echo "${USE_SHA256}  /tmp/use.zip" | sha256sum -c - \
 && unzip -q /tmp/use.zip -d /opt \
 && mv "/opt/use-${USE_VERSION}" /opt/use \
 && rm /tmp/use.zip

ENV LD_LIBRARY_PATH=/opt/use/lib
WORKDIR /work
# -nr: USE ships no GNU Readline library for Linux; without the flag it warns on every start
ENTRYPOINT ["java", "-Dawt.useSystemAAFontSettings=on", "-jar", "/opt/use/lib/use-gui.jar", "-nr"]
