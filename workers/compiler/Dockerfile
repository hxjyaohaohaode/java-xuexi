# Build on the dedicated worker host, inspect the resulting image, and record its digest.
# The broker resolves this tag to an immutable local image id at startup.
FROM eclipse-temurin:17-jdk-jammy
RUN apt-get update && apt-get install -y --no-install-recommends python3 && rm -rf /var/lib/apt/lists/*
COPY --chmod=0444 runner.py /opt/typeforge/runner.py
USER 65534:65534
WORKDIR /tmp
ENTRYPOINT []
CMD ["javac", "-version"]
