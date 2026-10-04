# ingest

Weekly batch job that builds the food-only product catalogue.

```bash
uv sync
uv run pytest
uv run ruff check . && uv run mypy
uv run mealplanner-ingest
```
