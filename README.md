# Meal Planner

> Plan a week of healthy meals **within your budget**, using **real products from German discount supermarkets**.
> Set your budget, calorie/protein goal and food style. Get a shopping list of real Aldi Süd products and a
> breakfast/lunch/dinner plan that uses exactly what you bought, with minimal waste.

## Engineering highlights
- **Constrained optimisation:** a mixed-integer model (OR-Tools CP-SAT) guarantees budget, calories and protein, with real pack sizes and minimal waste
- **Solver computes, LLM writes:** the LLM only adapts recipe text; every output is validated against the solver's ingredient list
- **Real-world data:** a polite crawler, a food-only filter and Open Food Facts enrichment turn a supermarket website into a clean catalogue
- **Modular monolith:** one Spring Boot backend with Spring Modulith boundaries enforced in tests, not microservices by default
- **€0 to run:** designed around free tiers (Vercel, Neon, Upstash, Gemini) with graceful degradation

## Architecture
```
Next.js (PWA) ──REST──► Spring Boot modular monolith ──► Postgres (schema per module)
                         catalog · recipes · profile · planning · mealplan    ▲
                                                                               │
                          Python ingest (weekly GitHub Action) ────────────────┘
```
| Part | Stack |
|---|---|
| `apps/web` | Next.js 16, React 19, TypeScript, Tailwind, TanStack Query, next-intl, Auth.js |
| `backend` | Java 21, Spring Boot 4, Spring Modulith, Spring Data JDBC, Flyway, Spring AI, OR-Tools |
| `ingest` | Python 3.13, httpx, selectolax, pydantic, psycopg |
| infra | Docker Compose (local), Terraform, GitHub Actions |

## Run locally
Prerequisites: Docker, Node 22 + pnpm, Python 3.13 + uv. Java is downloaded automatically by Gradle.

```bash
pnpm install
make backend   # starts Postgres + Redis via Docker Compose, then the API on :8080
make web       # Next.js on :3000
make test      # all test suites
```
API docs: http://localhost:8080/swagger-ui.html · health: http://localhost:8080/actuator/health

## Repository layout
```
apps/web/            Next.js app
backend/             Spring Boot modular monolith (modules: catalog, recipes, profile, planning, mealplan, api, shared)
ingest/              Python crawler + food filter
packages/contracts/  OpenAPI spec + generated TS types
infra/terraform/     free-tier infrastructure
```

## Data & legal
Product data is collected at low frequency from publicly accessible pages, in line with robots.txt, for a non-commercial project, and is not redistributed. Nutrition data enrichment: [Open Food Facts](https://world.openfoodfacts.org) (ODbL). The app gives no medical advice.
