package dev.boujelbene.mealplanner.planning.spike;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.ortools.Loader;
import com.google.ortools.sat.BoolVar;
import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.CpSolver;
import com.google.ortools.sat.CpSolverStatus;
import com.google.ortools.sat.IntVar;
import com.google.ortools.sat.LinearExpr;
import com.google.ortools.sat.LinearExprBuilder;
import com.google.ortools.sat.Literal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Exploratory solver test: can CP-SAT build a 7-day plan (shopping list + 21 meals) that respects budget, kcal and
 * protein in under 5 s? Run with {@code ./gradlew spikeTest}.
 *
 * <p>Simplified model: nutrition is per ingredient class (not per product), no shelf-life windows, no batch
 * cooking, no preferences. Prices are approximate 2026 Aldi Süd prices.
 */
@Tag("spike")
class SolverSpikeTest {

	enum Slot { BREAKFAST, LUNCH, DINNER }

	enum Shelf { PERISHABLE, CHILLED, SHELF_STABLE }

	record IngredientClass(String id, int kcal, double protein, double fat, double carbs, Shelf shelf) {}

	record Product(String name, String classId, int grams, int cents) {}

	record Recipe(String name, Slot slot, Map<String, Integer> gramsPerClass) {}

	record Profile(String name, int kcalTarget, int proteinTarget, int budgetCents) {}

	static final int DAYS = 7;
	static final int MAX_PACKS = 15;
	static final int MAX_USES_PER_RECIPE = 2;
	static final double BUDGET_BUFFER = 0.05;
	static final double KCAL_TOLERANCE = 0.10;

	// Nutrition per 100 g (ml treated as g)
	static final Map<String, IngredientClass> CLASSES = index(List.of(
		new IngredientClass("oats", 372, 13.5, 7.0, 58.7, Shelf.SHELF_STABLE),
		new IngredientClass("skyr", 60, 11.0, 0.2, 4.0, Shelf.CHILLED),
		new IngredientClass("yoghurt", 67, 3.6, 3.5, 5.3, Shelf.CHILLED),
		new IngredientClass("milk", 47, 3.4, 1.5, 4.9, Shelf.CHILLED),
		new IngredientClass("eggs", 143, 12.6, 9.9, 0.7, Shelf.CHILLED),
		new IngredientClass("banana", 93, 1.1, 0.2, 20.0, Shelf.PERISHABLE),
		new IngredientClass("apple", 54, 0.3, 0.2, 12.0, Shelf.PERISHABLE),
		new IngredientClass("bread", 220, 7.0, 1.5, 40.0, Shelf.PERISHABLE),
		new IngredientClass("rice", 350, 7.7, 0.8, 77.0, Shelf.SHELF_STABLE),
		new IngredientClass("pasta", 355, 12.0, 1.5, 71.0, Shelf.SHELF_STABLE),
		new IngredientClass("potatoes", 72, 2.0, 0.1, 15.0, Shelf.PERISHABLE),
		new IngredientClass("chicken_breast", 105, 23.0, 1.2, 0.0, Shelf.CHILLED),
		new IngredientClass("minced_beef", 200, 20.0, 13.0, 0.0, Shelf.CHILLED),
		new IngredientClass("tuna_canned", 110, 25.0, 1.0, 0.0, Shelf.SHELF_STABLE),
		new IngredientClass("lentils", 330, 24.0, 1.5, 50.0, Shelf.SHELF_STABLE),
		new IngredientClass("chickpeas_canned", 120, 7.0, 2.6, 14.0, Shelf.SHELF_STABLE),
		new IngredientClass("tomatoes_canned", 22, 1.2, 0.2, 3.5, Shelf.SHELF_STABLE),
		new IngredientClass("frozen_veg", 45, 2.5, 0.4, 6.0, Shelf.SHELF_STABLE),
		new IngredientClass("frozen_spinach", 25, 2.8, 0.4, 1.0, Shelf.SHELF_STABLE),
		new IngredientClass("frozen_berries", 45, 1.0, 0.3, 8.0, Shelf.SHELF_STABLE),
		new IngredientClass("onion", 40, 1.2, 0.1, 8.0, Shelf.PERISHABLE),
		new IngredientClass("bell_pepper", 30, 1.0, 0.3, 5.0, Shelf.PERISHABLE),
		new IngredientClass("cheese", 350, 24.0, 27.0, 0.0, Shelf.CHILLED),
		new IngredientClass("feta", 260, 17.0, 21.0, 0.5, Shelf.CHILLED),
		new IngredientClass("quark", 67, 12.0, 0.3, 4.0, Shelf.CHILLED),
		new IngredientClass("olive_oil", 884, 0.0, 100.0, 0.0, Shelf.SHELF_STABLE),
		new IngredientClass("peanut_butter", 610, 25.0, 50.0, 12.0, Shelf.SHELF_STABLE),
		new IngredientClass("tortilla", 300, 8.0, 6.0, 52.0, Shelf.SHELF_STABLE)));

	static final List<Product> PRODUCTS = List.of(
		new Product("Haferflocken 500 g", "oats", 500, 59),
		new Product("Skyr Natur 500 g", "skyr", 500, 119),
		new Product("Arla Skyr Natur 1 kg", "skyr", 1000, 199),
		new Product("Milsani Joghurt türk. Art 1 kg", "yoghurt", 1000, 209),
		new Product("Frische Milch 1,5 % 1 l", "milk", 1000, 99),
		new Product("Eier 10 Stück (~600 g)", "eggs", 600, 229),
		new Product("Bananen ~1 kg (estimated)", "banana", 1000, 139),
		new Product("Äpfel 1 kg (estimated)", "apple", 1000, 199),
		new Product("Vollkornbrot 500 g", "bread", 500, 119),
		new Product("Parboiled Reis 1 kg", "rice", 1000, 139),
		new Product("Basmati Reis 1 kg", "rice", 1000, 249),
		new Product("Spaghetti 500 g", "pasta", 500, 79),
		new Product("Kartoffeln 2 kg", "potatoes", 2000, 249),
		new Product("Hähnchenbrustfilet 600 g", "chicken_breast", 600, 599),
		new Product("Hähnchenbrustfilet XXL 1 kg", "chicken_breast", 1000, 899),
		new Product("Rinderhackfleisch 500 g", "minced_beef", 500, 499),
		new Product("Thunfisch Naturale 150 g (drained)", "tuna_canned", 150, 129),
		new Product("Rote Linsen 500 g", "lentils", 500, 149),
		new Product("Kichererbsen 240 g (drained)", "chickpeas_canned", 240, 69),
		new Product("Gehackte Tomaten 400 g", "tomatoes_canned", 400, 49),
		new Product("TK Gemüsemischung 750 g", "frozen_veg", 750, 149),
		new Product("TK Blattspinat 450 g", "frozen_spinach", 450, 99),
		new Product("TK Beerenmischung 500 g", "frozen_berries", 500, 249),
		new Product("Zwiebeln 1 kg (estimated)", "onion", 1000, 99),
		new Product("Paprika Mix 500 g (estimated)", "bell_pepper", 500, 199),
		new Product("Gouda 400 g", "cheese", 400, 229),
		new Product("Feta 200 g", "feta", 200, 139),
		new Product("Magerquark 500 g", "quark", 500, 99),
		new Product("Olivenöl 750 ml", "olive_oil", 690, 699),
		new Product("Erdnussbutter 350 g", "peanut_butter", 350, 199),
		new Product("Weizen-Tortillas 370 g", "tortilla", 370, 119));

	static final List<Recipe> RECIPES = List.of(
		new Recipe("Overnight oats with banana", Slot.BREAKFAST, Map.of("oats", 80, "milk", 200, "banana", 100, "peanut_butter", 15)),
		new Recipe("Skyr berry bowl", Slot.BREAKFAST, Map.of("skyr", 250, "frozen_berries", 100, "oats", 40)),
		new Recipe("Scrambled eggs on bread", Slot.BREAKFAST, Map.of("eggs", 180, "bread", 100, "bell_pepper", 50, "olive_oil", 5)),
		new Recipe("Quark with apple and oats", Slot.BREAKFAST, Map.of("quark", 250, "apple", 150, "oats", 40)),
		new Recipe("Yoghurt banana peanut bowl", Slot.BREAKFAST, Map.of("yoghurt", 250, "banana", 100, "oats", 30, "peanut_butter", 15)),
		new Recipe("Chicken rice bowl", Slot.LUNCH, Map.of("chicken_breast", 150, "rice", 90, "frozen_veg", 150, "olive_oil", 10)),
		new Recipe("Red lentil soup with bread", Slot.LUNCH, Map.of("lentils", 90, "tomatoes_canned", 200, "onion", 80, "olive_oil", 10, "bread", 60)),
		new Recipe("Tuna pasta salad", Slot.LUNCH, Map.of("pasta", 100, "tuna_canned", 150, "bell_pepper", 80, "olive_oil", 10)),
		new Recipe("Chickpea feta salad", Slot.LUNCH, Map.of("chickpeas_canned", 240, "feta", 60, "bell_pepper", 80, "onion", 40, "olive_oil", 10, "bread", 60)),
		new Recipe("Chicken wraps", Slot.LUNCH, Map.of("tortilla", 120, "chicken_breast", 120, "bell_pepper", 60, "yoghurt", 60)),
		new Recipe("Egg fried rice", Slot.LUNCH, Map.of("rice", 90, "eggs", 120, "frozen_veg", 150, "olive_oil", 10)),
		new Recipe("Spaghetti bolognese", Slot.DINNER, Map.of("pasta", 120, "minced_beef", 125, "tomatoes_canned", 200, "onion", 60, "olive_oil", 5)),
		new Recipe("Chicken, potatoes and spinach", Slot.DINNER, Map.of("chicken_breast", 150, "potatoes", 350, "frozen_spinach", 150, "olive_oil", 10)),
		new Recipe("Chili con carne with rice", Slot.DINNER, Map.of("minced_beef", 100, "chickpeas_canned", 120, "tomatoes_canned", 200, "onion", 60, "rice", 70, "bell_pepper", 60)),
		new Recipe("Veggie omelette with potatoes", Slot.DINNER, Map.of("eggs", 180, "potatoes", 300, "bell_pepper", 80, "cheese", 30, "olive_oil", 10)),
		new Recipe("Lentil spinach curry with rice", Slot.DINNER, Map.of("lentils", 80, "rice", 80, "tomatoes_canned", 200, "onion", 60, "frozen_spinach", 100, "olive_oil", 10)),
		new Recipe("Baked potatoes with quark and tuna", Slot.DINNER, Map.of("potatoes", 400, "quark", 150, "tuna_canned", 75, "onion", 30)),
		new Recipe("Pasta with spinach and feta", Slot.DINNER, Map.of("pasta", 120, "frozen_spinach", 150, "feta", 60, "onion", 40, "olive_oil", 10)));

	@BeforeAll
	static void loadNatives() {
		Loader.loadNativeLibraries();
	}

	static Stream<Arguments> profiles() {
		return Stream.of(
			Arguments.of(new Profile("A: Sam, lose fat", 2100, 140, 4500)),
			Arguments.of(new Profile("B: build muscle", 2700, 170, 5500)),
			Arguments.of(new Profile("C: tight budget", 2000, 150, 2200)));
	}

	/** Portion sizes in quarter servings: 0.75x, 1x, 1.25x, 1.5x. */
	static final int[] PORTIONS = {3, 4, 5, 6};

	record Solution(CpSolver solver, IntVar[] packs, BoolVar[][][] use, IntVar overBudget, IntVar[] proteinShort,
			IntVar[] kcalUnder, IntVar[] kcalOver) {}

	@ParameterizedTest(name = "{0}")
	@MethodSource("profiles")
	void buildsWeeklyPlan(Profile profile) {
		CpModel model = new CpModel();
		int nr = RECIPES.size();

		IntVar[] packs = new IntVar[PRODUCTS.size()];
		for (int p = 0; p < PRODUCTS.size(); p++) {
			packs[p] = model.newIntVar(0, MAX_PACKS, "packs_" + p);
		}

		// use[r][d][q]: recipe r is eaten on day d (in its own slot) with portion PORTIONS[q]
		BoolVar[][][] use = new BoolVar[nr][DAYS][PORTIONS.length];
		for (int r = 0; r < nr; r++) {
			List<Literal> allUses = new ArrayList<>();
			for (int d = 0; d < DAYS; d++) {
				for (int q = 0; q < PORTIONS.length; q++) {
					use[r][d][q] = model.newBoolVar("use_" + r + "_" + d + "_" + q);
					allUses.add(use[r][d][q]);
				}
			}
			model.addLessOrEqual(LinearExpr.sum(allUses.toArray(new BoolVar[0])), MAX_USES_PER_RECIPE);
		}
		for (int d = 0; d < DAYS; d++) {
			for (Slot slot : Slot.values()) {
				List<Literal> options = new ArrayList<>();
				for (int r = 0; r < nr; r++) {
					if (RECIPES.get(r).slot() == slot) {
						for (int q = 0; q < PORTIONS.length; q++) {
							options.add(use[r][d][q]);
						}
					}
				}
				model.addExactlyOne(options);
			}
		}

		// Linking (in quarter-grams): eaten per class <= bought per class; waste = bought - eaten
		LinearExprBuilder weightedWaste = LinearExpr.newBuilder();
		for (IngredientClass ic : CLASSES.values()) {
			int weight = switch (ic.shelf()) {
				case PERISHABLE -> 3;
				case CHILLED -> 2;
				case SHELF_STABLE -> 0; // leftovers go to the pantry, not the bin
			};
			LinearExprBuilder eaten = LinearExpr.newBuilder();
			LinearExprBuilder bought = LinearExpr.newBuilder();
			for (int r = 0; r < nr; r++) {
				int grams = RECIPES.get(r).gramsPerClass().getOrDefault(ic.id(), 0);
				if (grams == 0) {
					continue;
				}
				for (int d = 0; d < DAYS; d++) {
					for (int q = 0; q < PORTIONS.length; q++) {
						eaten.addTerm(use[r][d][q], (long) grams * PORTIONS[q]);
						weightedWaste.addTerm(use[r][d][q], (long) -weight * grams * PORTIONS[q]);
					}
				}
			}
			for (int p = 0; p < PRODUCTS.size(); p++) {
				if (PRODUCTS.get(p).classId().equals(ic.id())) {
					bought.addTerm(packs[p], 4L * PRODUCTS.get(p).grams());
					weightedWaste.addTerm(packs[p], 4L * weight * PRODUCTS.get(p).grams());
				}
			}
			model.addLessOrEqual(eaten, bought);
		}

		// Budget (elastic): cost <= budget * (1 - buffer) + overBudget
		long budgetLimit = Math.round(profile.budgetCents() * (1 - BUDGET_BUFFER));
		LinearExprBuilder cost = LinearExpr.newBuilder();
		for (int p = 0; p < PRODUCTS.size(); p++) {
			cost.addTerm(packs[p], PRODUCTS.get(p).cents());
		}
		IntVar overBudget = model.newIntVar(0, 100_000, "overBudgetCents");
		model.addLessOrEqual(LinearExpr.newBuilder().add(cost).addTerm(overBudget, -1), budgetLimit);

		// Daily kcal within +-10% and protein >= target, both elastic so infeasibility is explained, not just reported
		long kcalLow = Math.round(profile.kcalTarget() * (1 - KCAL_TOLERANCE));
		long kcalHigh = Math.round(profile.kcalTarget() * (1 + KCAL_TOLERANCE));
		IntVar[] proteinShort = new IntVar[DAYS];
		IntVar[] kcalUnder = new IntVar[DAYS];
		IntVar[] kcalOver = new IntVar[DAYS];
		for (int d = 0; d < DAYS; d++) {
			LinearExprBuilder kcal = LinearExpr.newBuilder();
			LinearExprBuilder protein = LinearExpr.newBuilder();
			for (int r = 0; r < nr; r++) {
				for (int q = 0; q < PORTIONS.length; q++) {
					kcal.addTerm(use[r][d][q], kcal(r, q));
					protein.addTerm(use[r][d][q], proteinDecigrams(r, q));
				}
			}
			kcalUnder[d] = model.newIntVar(0, 5_000, "kcalUnder_" + d);
			kcalOver[d] = model.newIntVar(0, 5_000, "kcalOver_" + d);
			proteinShort[d] = model.newIntVar(0, 3_000, "proteinShort_" + d);
			model.addGreaterOrEqual(LinearExpr.newBuilder().add(kcal).addTerm(kcalUnder[d], 1), kcalLow);
			model.addLessOrEqual(LinearExpr.newBuilder().add(kcal).addTerm(kcalOver[d], -1), kcalHigh);
			model.addGreaterOrEqual(LinearExpr.newBuilder().add(protein).addTerm(proteinShort[d], 1), profile.proteinTarget() * 10L);
		}

		// Objective: slack is very expensive (it explains infeasibility), then waste and cost
		LinearExprBuilder objective = LinearExpr.newBuilder().addTerm(overBudget, 10_000).add(weightedWaste);
		for (int d = 0; d < DAYS; d++) {
			objective.addTerm(proteinShort[d], 10_000).addTerm(kcalUnder[d], 10_000).addTerm(kcalOver[d], 10_000);
		}
		for (int p = 0; p < PRODUCTS.size(); p++) {
			objective.addTerm(packs[p], 8L * PRODUCTS.get(p).cents());
		}
		model.minimize(objective);

		CpSolver solver = new CpSolver();
		solver.getParameters().setMaxTimeInSeconds(Double.parseDouble(System.getProperty("spike.seconds", "5")));
		solver.getParameters().setNumWorkers(8);
		CpSolverStatus status = solver.solve(model);

		assertThat(status).isIn(CpSolverStatus.OPTIMAL, CpSolverStatus.FEASIBLE);
		Solution solution = new Solution(solver, packs, use, overBudget, proteinShort, kcalUnder, kcalOver);
		report(profile, status, solution, budgetLimit, model);
		verifyIndependently(profile, solution, budgetLimit, kcalLow, kcalHigh);
	}

	/** Re-checks the solution outside the model: every hard constraint must hold (the property the real tests will generalise). */
	private static void verifyIndependently(Profile profile, Solution s, long budgetLimit, long kcalLow, long kcalHigh) {
		CpSolver solver = s.solver();
		long cost = 0;
		Map<String, Long> boughtQuarterGrams = new LinkedHashMap<>();
		for (int p = 0; p < PRODUCTS.size(); p++) {
			long n = solver.value(s.packs()[p]);
			cost += n * PRODUCTS.get(p).cents();
			boughtQuarterGrams.merge(PRODUCTS.get(p).classId(), 4 * n * PRODUCTS.get(p).grams(), Long::sum);
		}
		assertThat(cost).isLessThanOrEqualTo(budgetLimit + solver.value(s.overBudget()));
		Map<String, Long> eatenQuarterGrams = new LinkedHashMap<>();
		for (int d = 0; d < DAYS; d++) {
			long kcal = 0;
			long protein = 0;
			int meals = 0;
			for (int r = 0; r < RECIPES.size(); r++) {
				for (int q = 0; q < PORTIONS.length; q++) {
					if (solver.booleanValue(s.use()[r][d][q])) {
						meals++;
						kcal += kcal(r, q);
						protein += proteinDecigrams(r, q);
						int portion = PORTIONS[q];
						RECIPES.get(r).gramsPerClass().forEach((c, g) -> eatenQuarterGrams.merge(c, (long) g * portion, Long::sum));
					}
				}
			}
			assertThat(meals).isEqualTo(3);
			assertThat(kcal + solver.value(s.kcalUnder()[d])).isGreaterThanOrEqualTo(kcalLow);
			assertThat(kcal - solver.value(s.kcalOver()[d])).isLessThanOrEqualTo(kcalHigh);
			assertThat(protein + solver.value(s.proteinShort()[d])).isGreaterThanOrEqualTo(profile.proteinTarget() * 10L);
		}
		eatenQuarterGrams.forEach((c, g) -> assertThat(boughtQuarterGrams.getOrDefault(c, 0L)).as("enough %s bought", c).isGreaterThanOrEqualTo(g));
	}

	private static void report(Profile profile, CpSolverStatus status, Solution s, long budgetLimit, CpModel model) {
		CpSolver solver = s.solver();
		StringBuilder out = new StringBuilder("\n=== ").append(profile.name()).append(" ===\n");
		out.append("status=%s wall=%.2fs vars=%d constraints=%d%n".formatted(status, solver.wallTime(),
			model.model().getVariablesCount(), model.model().getConstraintsCount()));
		long cost = 0;
		out.append("Shopping list:\n");
		for (int p = 0; p < PRODUCTS.size(); p++) {
			long n = solver.value(s.packs()[p]);
			if (n > 0) {
				cost += n * PRODUCTS.get(p).cents();
				out.append("  %d x %-38s %6.2f EUR%n".formatted(n, PRODUCTS.get(p).name(), n * PRODUCTS.get(p).cents() / 100.0));
			}
		}
		out.append("total=%.2f EUR (limit incl. 5%% buffer %.2f)%n".formatted(cost / 100.0, budgetLimit / 100.0));
		long proteinShort = 0;
		long kcalMiss = 0;
		for (int d = 0; d < DAYS; d++) {
			long kcal = 0;
			long protein = 0;
			List<String> meals = new ArrayList<>();
			for (int r = 0; r < RECIPES.size(); r++) {
				for (int q = 0; q < PORTIONS.length; q++) {
					if (solver.booleanValue(s.use()[r][d][q])) {
						kcal += kcal(r, q);
						protein += proteinDecigrams(r, q);
						meals.add(RECIPES.get(r).name() + " (" + PORTIONS[q] / 4.0 + "x)");
					}
				}
			}
			proteinShort += solver.value(s.proteinShort()[d]);
			kcalMiss += solver.value(s.kcalUnder()[d]) + solver.value(s.kcalOver()[d]);
			out.append("  day %d: %4d kcal %5.1f g protein | %s%n".formatted(d + 1, kcal, protein / 10.0, String.join(" · ", meals)));
		}
		long perishableBought = 0;
		long perishableLeft = 0;
		for (IngredientClass ic : CLASSES.values()) {
			if (ic.shelf() == Shelf.SHELF_STABLE) {
				continue;
			}
			long bought = 0;
			for (int p = 0; p < PRODUCTS.size(); p++) {
				if (PRODUCTS.get(p).classId().equals(ic.id())) {
					bought += 4 * solver.value(s.packs()[p]) * PRODUCTS.get(p).grams();
				}
			}
			long eaten = 0;
			for (int r = 0; r < RECIPES.size(); r++) {
				int grams = RECIPES.get(r).gramsPerClass().getOrDefault(ic.id(), 0);
				for (int d = 0; d < DAYS; d++) {
					for (int q = 0; q < PORTIONS.length; q++) {
						eaten += solver.booleanValue(s.use()[r][d][q]) ? (long) grams * PORTIONS[q] : 0;
					}
				}
			}
			perishableBought += bought;
			perishableLeft += bought - eaten;
		}
		out.append("perishable/chilled waste: %d g of %d g bought (%.0f%%)%n".formatted(perishableLeft / 4, perishableBought / 4,
			100.0 * perishableLeft / Math.max(perishableBought, 1)));
		if (solver.value(s.overBudget()) > 0 || proteinShort > 0 || kcalMiss > 0) {
			out.append("EXPLANATION: over budget by %.2f EUR; protein short by %.1f g/day; kcal outside range by %d kcal/day (avg)%n"
				.formatted(solver.value(s.overBudget()) / 100.0, proteinShort / 10.0 / DAYS, kcalMiss / DAYS));
		}
		System.out.print(out);
	}

	static long kcal(int recipe, int portionIndex) {
		return Math.round(recipeKcalExact(RECIPES.get(recipe)) * PORTIONS[portionIndex] / 4.0);
	}

	static long proteinDecigrams(int recipe, int portionIndex) {
		return Math.round(recipeProteinDecigramsExact(RECIPES.get(recipe)) * PORTIONS[portionIndex] / 4.0);
	}

	static double recipeKcalExact(Recipe recipe) {
		return recipe.gramsPerClass().entrySet().stream()
			.mapToDouble(e -> e.getValue() * CLASSES.get(e.getKey()).kcal() / 100.0).sum();
	}

	static double recipeProteinDecigramsExact(Recipe recipe) {
		return recipe.gramsPerClass().entrySet().stream()
			.mapToDouble(e -> e.getValue() * CLASSES.get(e.getKey()).protein() / 10.0).sum();
	}

	private static Map<String, IngredientClass> index(List<IngredientClass> classes) {
		Map<String, IngredientClass> map = new LinkedHashMap<>();
		classes.forEach(c -> map.put(c.id(), c));
		return map;
	}

}
