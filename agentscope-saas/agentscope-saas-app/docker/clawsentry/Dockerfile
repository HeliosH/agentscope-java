# ClawSentry Gateway image used by the AgentScope SaaS local and private deployments.
# The source revision is pinned so a deployment does not silently change its policy engine.
FROM python:3.12-slim AS builder

ARG CLAWSENTRY_REPOSITORY=https://github.com/AI45Lab/ClawSentry.git
ARG CLAWSENTRY_REF=2a3a826bb731cb18db88f84be9a6dd3974a321f9

WORKDIR /build
RUN apt-get update \
    && apt-get install -y --no-install-recommends git ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && git clone --depth 1 "${CLAWSENTRY_REPOSITORY}" ClawSentry \
    && cd ClawSentry \
    && git fetch --depth 1 origin "${CLAWSENTRY_REF}" \
    && git checkout --detach "${CLAWSENTRY_REF}"

# The host build environment may inject PIP_REQUIRE_HASHES for internal wheels. ClawSentry's
# pyproject dependencies are resolved from the pinned source revision, so do not inherit that
# unrelated host policy into this isolated image build.
RUN PIP_REQUIRE_HASHES=0 pip install --isolated --no-cache-dir --prefix=/install /build/ClawSentry\[metrics\]

FROM python:3.12-slim AS runtime

RUN groupadd -r clawsentry && useradd -r -g clawsentry -m clawsentry
COPY --from=builder /install /usr/local

RUN mkdir -p /data && chown clawsentry:clawsentry /data
VOLUME ["/data"]

ENV CS_HTTP_HOST=0.0.0.0 \
    CS_HTTP_PORT=8080 \
    CS_TRAJECTORY_DB_PATH=/data/clawsentry-trajectory.db

EXPOSE 8080
USER clawsentry
WORKDIR /home/clawsentry

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8080/health')" || exit 1

ENTRYPOINT ["clawsentry-gateway"]
CMD ["--host", "0.0.0.0", "--port", "8080"]
