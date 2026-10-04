.PHONY: help up down backend web ingest test

help:            ## List targets
	@grep -E '^[a-z-]+:.*##' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  %-10s %s\n", $$1, $$2}'

up:              ## Start local Postgres + Redis
	docker compose up -d

down:            ## Stop local services
	docker compose down

backend:         ## Run the Spring Boot backend (starts compose services automatically)
	cd backend && ./gradlew bootRun

web:             ## Run the Next.js dev server
	pnpm dev:web

ingest:          ## Run the ingest job
	cd ingest && uv run mealplanner-ingest

test:            ## Run all test suites
	cd backend && ./gradlew test
	pnpm --filter @meal-planner/web test
	cd ingest && uv run pytest
