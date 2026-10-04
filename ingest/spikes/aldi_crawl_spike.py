"""Exploratory crawl of 3 Aldi Süd food categories to measure data quality.

Answers one question: can we get price, pack size and nutrition for enough products,
politely and reliably? The production crawler reuses the parsing ideas, not this file.

Run: uv run python spikes/aldi_crawl_spike.py
"""

from __future__ import annotations

import json
import re
import time
from dataclasses import asdict, dataclass, field
from pathlib import Path
from urllib.robotparser import RobotFileParser

import httpx
from selectolax.parser import HTMLParser

BASE = "https://www.aldi-sued.de"
USER_AGENT = (
    "mealplanner-crawler/0.1 (+https://github.com/bechirboujelbene; non-commercial student project)"
)
DELAY_SECONDS = 1.5
CATEGORIES = {
    "naturjoghurt-skyr": "/produkte/milchprodukte-eier/naturjoghurt-skyr/k/1588161425467097",
    "reis": "/produkte/nudeln-reis-huelsenfruechte/reis/k/1588161425467117",
    "gefluegel": "/produkte/fleisch-fisch/gefluegel/k/1588161425467059",
}
RAW_DIR = Path(__file__).resolve().parents[1] / "data" / "raw" / "spike"
OUT_FILE = Path(__file__).resolve().parents[1] / "data" / "spike_products.jsonl"

NUTRIENT_KEYS = {
    "Energie [kcal]": "kcal",
    "Fett": "fat_g",
    "Fett, davon gesättigte Fettsäuren": "saturated_fat_g",
    "Kohlenhydrate": "carbs_g",
    "Kohlenhydrate, davon Zucker": "sugar_g",
    "Eiweiß": "protein_g",
    "Salz Äquivalent": "salt_g",
    "Salz": "salt_g",
}
REQUIRED_NUTRIENTS = ("kcal", "protein_g", "fat_g", "carbs_g")

# "1 kg", "500 g", "1,5 l", "6 x 125 g", "10 Stück"
MULTI_RE = re.compile(r"(\d+)\s*[x\u00d7]\s*(\d+(?:[.,]\d+)?)\s*(kg|g|ml|l)\b", re.IGNORECASE)
SINGLE_RE = re.compile(r"(\d+(?:[.,]\d+)?)\s*(kg|g|ml|l)\b", re.IGNORECASE)
PIECES_RE = re.compile(r"(\d+)\s*(?:Stück|Stk\.?)\b", re.IGNORECASE)
TO_BASE = {"kg": 1000.0, "g": 1.0, "l": 1000.0, "ml": 1.0}


@dataclass
class Product:
    url: str
    external_id: str
    name: str | None = None
    brand: str | None = None
    price_cents: int | None = None
    category_path: list[str] = field(default_factory=list)
    pack_amount: float | None = None  # grams or millilitres
    pack_unit: str | None = None  # "g" | "ml" | "piece"
    chilled: bool = False
    nutrition_per_100: dict[str, float] = field(default_factory=dict)
    nutrition_basis: str | None = None  # "100 g" | "100 ml"
    issues: list[str] = field(default_factory=list)


def parse_number(text: str) -> float | None:
    m = re.search(r"-?\d+(?:[.,]\d+)?", text.replace("\u00a0", " "))
    return float(m.group(0).replace(",", ".")) if m else None


def parse_pack_size(name: str) -> tuple[float | None, str | None]:
    """Parses German pack sizes from a product name into (amount in g/ml, unit)."""
    if m := MULTI_RE.search(name):
        count, amount, unit = int(m.group(1)), float(m.group(2).replace(",", ".")), m.group(3)
        return count * amount * TO_BASE[unit.lower()], "ml" if unit.lower() in ("l", "ml") else "g"
    matches = SINGLE_RE.findall(name)
    if matches:
        amount, unit = matches[-1]  # the last quantity is usually the pack size
        value = float(amount.replace(",", ".")) * TO_BASE[unit.lower()]
        return value, "ml" if unit.lower() in ("l", "ml") else "g"
    if m := PIECES_RE.search(name):
        return float(m.group(1)), "piece"
    return None, None


def parse_product(url: str, html: str) -> Product:
    external_id = url.rsplit("-", 1)[-1]
    product = Product(url=url, external_id=external_id)
    tree = HTMLParser(html)

    for node in tree.css('script[type="application/ld+json"]'):
        try:
            data = json.loads(node.text())
        except json.JSONDecodeError:
            continue
        if data.get("@type") == "Product":
            product.name = data.get("name")
            product.brand = (data.get("brand") or {}).get("name")
            price = (data.get("offers") or {}).get("price")
            if price is not None:
                product.price_cents = round(float(price) * 100)
        elif data.get("@type") == "BreadcrumbList":
            names = [item["name"] for item in data.get("itemListElement", [])]
            product.category_path = names[2:-1]  # drop "Startseite", "Produkte" and the product

    table = tree.css_first("#nährwerte table")
    if table is not None:
        header = table.css_first("thead")
        if header is not None and (basis := re.search(r"100\s*(g|ml)", header.text())):
            product.nutrition_basis = f"100 {basis.group(1)}"
        for row in table.css("tbody tr"):
            cells = [c.text(strip=True) for c in row.css("td")]
            if len(cells) >= 2 and cells[0] in NUTRIENT_KEYS:
                value = parse_number(cells[1])
                if value is not None:
                    product.nutrition_per_100[NUTRIENT_KEYS[cells[0]]] = value

    product.chilled = any(
        "Kühlung" in label.text() for label in tree.css(".product-details__text-badges .base-label")
    )
    if product.name:
        product.pack_amount, product.pack_unit = parse_pack_size(product.name)

    if not product.name:
        product.issues.append("no_name")
    if product.price_cents is None:
        product.issues.append("no_price")
    if product.pack_amount is None:
        product.issues.append("no_pack_size")
    missing = [k for k in REQUIRED_NUTRIENTS if k not in product.nutrition_per_100]
    if missing:
        product.issues.append("missing_nutrients:" + ",".join(missing))
    return product


class PoliteClient:
    def __init__(self) -> None:
        self.http = httpx.Client(
            headers={"User-Agent": USER_AGENT, "Accept-Language": "de-DE"},
            timeout=20,
            follow_redirects=True,
        )
        self.robots = RobotFileParser(BASE + "/robots.txt")
        self.robots.read()
        self.requests = 0
        self.status_counts: dict[int, int] = {}

    def get(self, path: str) -> str | None:
        url = path if path.startswith("http") else BASE + path
        if not self.robots.can_fetch(USER_AGENT, url):
            print(f"  robots.txt disallows {url}")
            return None
        cache = RAW_DIR / (re.sub(r"[^a-zA-Z0-9]+", "_", url.removeprefix(BASE))[:150] + ".html")
        if cache.exists():
            return cache.read_text(encoding="utf-8")
        time.sleep(DELAY_SECONDS)
        response = self.http.get(url)
        self.requests += 1
        self.status_counts[response.status_code] = (
            self.status_counts.get(response.status_code, 0) + 1
        )
        if response.status_code != 200:
            print(f"  HTTP {response.status_code} for {url}")
            return None
        cache.write_text(response.text, encoding="utf-8")
        return response.text


def product_links(html: str) -> list[str]:
    """Links from the category's product grid only (not recommendation carousels)."""
    grid = HTMLParser(html).css_first(".product-listing-viewer")
    if grid is None:
        return []
    hrefs = (a.attributes.get("href") or "" for a in grid.css('a[href^="/produkt/"]'))
    return sorted({h.split("?")[0].split("#")[0] for h in hrefs})


def main() -> None:
    RAW_DIR.mkdir(parents=True, exist_ok=True)
    client = PoliteClient()
    started = time.monotonic()
    products: list[Product] = []

    for key, path in CATEGORIES.items():
        links: list[str] = []
        page = 1
        while True:
            html = client.get(path if page == 1 else f"{path}?page={page}")
            if html is None:
                break
            new = [link for link in product_links(html) if link not in links]
            if not new:
                break
            links += new
            page += 1
        print(f"{key}: {len(links)} product links over {page - 1} page(s)")
        for link in links:
            html = client.get(link)
            if html is not None:
                products.append(parse_product(BASE + link, html))

    OUT_FILE.write_text(
        "\n".join(json.dumps(asdict(p), ensure_ascii=False) for p in products), encoding="utf-8"
    )
    complete = [p for p in products if not p.issues]
    print("\n=== summary ===")
    print(f"products parsed:        {len(products)}")
    print(f"complete (all fields):  {len(complete)} ({len(complete) / max(len(products), 1):.0%})")
    issue_counts: dict[str, int] = {}
    for p in products:
        for issue in p.issues:
            issue_counts[issue] = issue_counts.get(issue, 0) + 1
    for issue, count in sorted(issue_counts.items(), key=lambda kv: -kv[1]):
        print(f"  {issue}: {count}")
    print(f"chilled:                {sum(p.chilled for p in products)}")
    print(f"HTTP requests:          {client.requests} {client.status_counts}")
    print(f"elapsed:                {time.monotonic() - started:.0f}s")


if __name__ == "__main__":
    main()
