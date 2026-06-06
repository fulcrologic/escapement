# Constrained-box benchmark image (Babashka runtime — the shipped product).
#
# Builds the scale harness against whatever src/ is in the build context, so the
# SAME Dockerfile produces both the "before" (main) and "after" (this branch)
# images depending on which checkout you build from. See docs/benchmark.md.
FROM eclipse-temurin:21-jdk
RUN apt-get update && apt-get install -y --no-install-recommends curl bash \
    && rm -rf /var/lib/apt/lists/* \
    && curl -sL https://raw.githubusercontent.com/babashka/babashka/master/install | bash
WORKDIR /app
# Dep manifests + source; warm the maven cache into an image layer so the
# constrained `docker run`s never download or re-resolve at runtime.
COPY bb.edn deps.edn ./
COPY src ./src
COPY bench ./bench
RUN bb -e "(require 'escapement.lib 'escapement.chart.helpers 'com.fulcrologic.statecharts.chart 'taoensso.timbre) (println :warmed)"
# SCALE_VT routes the SESSION executor onto virtual threads in BOTH images, so
# the only platform-thread difference between main and this branch is
# escapement's own worker threads (transcript-writer + llm-conversation worker).
# That isolates exactly what the virtual-thread change affects.
ENV SCALE_VT=1
ENTRYPOINT ["bb", "bench/scale_test.clj"]
