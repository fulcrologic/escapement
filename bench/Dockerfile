FROM eclipse-temurin:21-jdk
RUN apt-get update && apt-get install -y --no-install-recommends curl bash \
    && rm -rf /var/lib/apt/lists/* \
    && curl -sL https://raw.githubusercontent.com/babashka/babashka/master/install | bash
WORKDIR /app
# Copy dep manifests + source; warm the maven cache into an image layer so
# constrained `docker run`s never download or re-resolve at runtime.
COPY bb.edn deps.edn ./
COPY src ./src
COPY bench ./bench
RUN bb -e "(require 'escapement.lib 'escapement.chart.helpers 'com.fulcrologic.statecharts.chart 'taoensso.timbre) (println :warmed)"
ENTRYPOINT ["bb", "bench/scale_test.clj"]
