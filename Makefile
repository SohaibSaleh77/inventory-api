# =============================================================================
# inventory-api Makefile - developer and ops entry points.
# Tools may be absent or overridden:  make build MVN=/opt/maven/bin/mvn
# =============================================================================

MVN ?= mvn
JAVA ?= java
COMPOSE ?= docker compose
KUBECTL ?= kubectl

.DEFAULT_GOAL := help
.PHONY: help build test verify run smoke docker-build docker-up docker-down \
        docker-logs k8s-apply clean

help: ## Show this help
	@awk 'BEGIN {FS = ":.*## "} /^[a-zA-Z0-9_-]+:.*## / {printf "  \033[36m%-14s\033[0m %s\n", $$1, $$2}' $(MAKEFILE_LIST)

build: ## Package the thin jar (target/inventory-api.jar + target/lib), skip tests
	$(MVN) -q -DskipTests package

test: ## Run unit tests only (no database required)
	$(MVN) -q test

verify: ## Run the full verification suite (needs MySQL test DB, see README)
	$(MVN) -B verify

run: ## Run the API locally in the foreground (needs target/ built)
	$(JAVA) -cp "target/inventory-api.jar:target/lib/*" com.ims.Main

smoke: ## Run the end-to-end smoke test against a running server
	bash scripts/e2e-smoke.sh

docker-build: ## Build the container image and tag it inventory-api:local
	docker build -t inventory-api:local .

docker-up: ## Start the compose stack (db + migrate + app); needs .env
	$(COMPOSE) up -d --build

docker-down: ## Stop the compose stack (add -v on the CLI to wipe the DB volume)
	$(COMPOSE) down

docker-logs: ## Tail logs of all compose services
	$(COMPOSE) logs -f

k8s-apply: ## Apply the Kubernetes manifests via kustomize
	$(KUBECTL) apply -k k8s/

clean: ## Remove build output and local logs
	$(MVN) clean
	rm -rf logs
