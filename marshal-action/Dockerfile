FROM eclipse-temurin:21-jre-alpine

# curl: GitHub API calls; jq: JSON parsing; git: base POM retrieval
RUN apk add --no-cache bash curl git jq

# Fat-jar built by :marshal-cli:shadowJar.
# CI copies it here before building the image:
#   cp marshal-cli/build/libs/marshal-cli-*.jar marshal-action/
COPY marshal-cli.jar /opt/marshal/marshal.jar

# Thin wrapper so entrypoint can call `marshal` like a native binary
RUN printf '#!/bin/sh\nexec java -jar /opt/marshal/marshal.jar "$@"\n' \
        > /usr/local/bin/marshal && \
    chmod +x /usr/local/bin/marshal

COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

ENTRYPOINT ["/entrypoint.sh"]
