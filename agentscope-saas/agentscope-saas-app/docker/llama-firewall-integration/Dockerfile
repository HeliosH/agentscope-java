FROM python:3.11-slim

ARG LLAMA_FIREWALL_VERSION=1.0.3
ARG TORCH_VERSION=2.4.1
ARG TRANSFORMERS_VERSION=4.57.3

RUN pip install --no-cache-dir \
        --index-url https://download.pytorch.org/whl/cpu \
        "torch==${TORCH_VERSION}" \
    && pip install --no-cache-dir \
        "transformers==${TRANSFORMERS_VERSION}" \
        "llamafirewall==${LLAMA_FIREWALL_VERSION}" \
    && groupadd --system llamafirewall \
    && useradd --system --gid llamafirewall --create-home llamafirewall

# CodeShield creates its osemgrep compatibility link on first import. Do that while the image
# still runs as root so the unprivileged runtime user never writes to site-packages.
RUN python -c "from codeshield.insecure_code_detector import insecure_code_detector"

WORKDIR /service
COPY agentscope-saas/agentscope-saas-app/docker/llama-firewall-integration/server.py /service/server.py

ENV LF_HTTP_HOST=0.0.0.0 \
    LF_HTTP_PORT=8080 \
    PYTHONUNBUFFERED=1

RUN chown -R llamafirewall:llamafirewall /service

USER llamafirewall
EXPOSE 8080

ENTRYPOINT ["python", "/service/server.py"]
