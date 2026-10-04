/** Formats integer cents as a Euro amount, e.g. 4320 -> "43,20 €" (de) or "€43.20" (en). */
export function formatCents(cents: number, locale: "de" | "en" = "de"): string {
  return new Intl.NumberFormat(locale === "de" ? "de-DE" : "en-IE", {
    style: "currency",
    currency: "EUR",
  }).format(cents / 100);
}
