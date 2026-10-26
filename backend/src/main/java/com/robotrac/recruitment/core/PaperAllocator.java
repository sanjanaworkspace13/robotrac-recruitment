package com.robotrac.recruitment.core;

import com.robotrac.recruitment.domain.Difficulty;
import com.robotrac.recruitment.domain.Section;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Random;

/**
 * Decides HOW MANY questions of each (section, difficulty) cell go into one paper.
 *
 * <h2>Allocation policy (documented in docs/database-design.md and README)</h2>
 * <ol>
 *   <li>The difficulty mix is exact: e.g. 5 Easy + 15 Moderate + 10 Hard.</li>
 *   <li>With section balancing ON (default), the paper is split as evenly as possible across the
 *       three sections (30 -> 10/10/10). If the total is not divisible by 3, the extra questions go to
 *       randomly chosen sections.</li>
 *   <li>Inside each section, each difficulty is spread as evenly as the available questions allow
 *       (e.g. Easy 2/2/1, Moderate 5/5/5, Hard 3/3/4), with random tie-breaking per candidate.</li>
 *   <li>Every step is checked with a max-flow feasibility test, so if any valid allocation exists one
 *       is always found; if none exists, a clear administrative error lists what is missing.</li>
 *   <li>With section balancing OFF, questions of each difficulty are drawn from the whole bank.</li>
 * </ol>
 * This class has no Spring/JPA dependencies so it can be unit-tested directly.
 */
public final class PaperAllocator {

    public static final int SECTIONS = Section.values().length;
    public static final int DIFFICULTIES = Difficulty.values().length;

    private PaperAllocator() {
    }

    /** Result of a feasibility check (used before activating a drive). */
    public record Feasibility(boolean feasible, List<String> problems, int[][] examplePlan) {
    }

    /** Thrown when no valid paper can be produced. */
    public static class AllocationException extends RuntimeException {
        private final List<String> problems;

        public AllocationException(List<String> problems) {
            super(String.join(" ", problems));
            this.problems = List.copyOf(problems);
        }

        public List<String> getProblems() {
            return problems;
        }
    }

    /**
     * @param available available[section.ordinal()][difficulty.ordinal()] eligible questions
     * @param need      need[difficulty.ordinal()] questions required
     */
    public static Feasibility check(int[][] available, int[] need, boolean sectionBalanced) {
        try {
            int[][] plan = allocate(available, need, sectionBalanced, null);
            return new Feasibility(true, List.of(), plan);
        } catch (AllocationException e) {
            return new Feasibility(false, e.getProblems(), null);
        }
    }

    /**
     * Produces counts[section][difficulty]. Pass a Random for per-candidate variety, or null for a
     * deterministic plan (used for feasibility checks).
     */
    public static int[][] allocate(int[][] available, int[] need, boolean sectionBalanced, Random random) {
        validateShape(available, need);
        int total = Arrays.stream(need).sum();
        if (total <= 0) {
            throw new AllocationException(List.of("The paper must contain at least one question."));
        }

        List<String> problems = new ArrayList<>();
        for (Difficulty d : Difficulty.values()) {
            int col = columnSum(available, d.ordinal());
            if (col < need[d.ordinal()]) {
                problems.add(String.format("Not enough eligible %s questions: need %d, available %d.",
                        d.name(), need[d.ordinal()], col));
            }
        }
        if (!problems.isEmpty()) {
            throw new AllocationException(problems);
        }

        if (!sectionBalanced) {
            return allocateUnbalanced(available, need, random);
        }

        int base = total / SECTIONS;
        int remainder = total % SECTIONS;
        for (int[] permutation : sectionPermutations(random)) {
            int[] target = new int[SECTIONS];
            Arrays.fill(target, base);
            for (int i = 0; i < remainder; i++) {
                target[permutation[i]]++;
            }
            if (maxFlow(available, need, target) == total) {
                return greedyFill(available, need, target, random);
            }
        }

        StringBuilder detail = new StringBuilder();
        for (Section s : Section.values()) {
            int[] row = available[s.ordinal()];
            detail.append(String.format(" %s: %d easy / %d moderate / %d hard;", s.getLabel(),
                    row[Difficulty.EASY.ordinal()], row[Difficulty.MODERATE.ordinal()], row[Difficulty.HARD.ordinal()]));
        }
        throw new AllocationException(List.of(String.format(
                "The paper cannot be split evenly across the three sections (about %d questions each) with the "
                        + "eligible questions available.%s Add or approve more questions, change the difficulty mix, "
                        + "or turn off section balancing.", base, detail)));
    }

    private static int[][] allocateUnbalanced(int[][] available, int[] need, Random random) {
        int[][] counts = new int[SECTIONS][DIFFICULTIES];
        for (int d = 0; d < DIFFICULTIES; d++) {
            int[] left = new int[SECTIONS];
            for (int s = 0; s < SECTIONS; s++) {
                left[s] = available[s][d];
            }
            for (int k = 0; k < need[d]; k++) {
                int chosen = pickSection(left, random);
                counts[chosen][d]++;
                left[chosen]--;
            }
        }
        return counts;
    }

    /** Uniform over remaining questions (random) or the section with the most remaining (deterministic). */
    private static int pickSection(int[] left, Random random) {
        int sum = Arrays.stream(left).sum();
        if (random == null) {
            int best = 0;
            for (int s = 1; s < left.length; s++) {
                if (left[s] > left[best]) {
                    best = s;
                }
            }
            return best;
        }
        int r = random.nextInt(sum);
        for (int s = 0; s < left.length; s++) {
            if (r < left[s]) {
                return s;
            }
            r -= left[s];
        }
        throw new IllegalStateException("unreachable");
    }

    private static int[][] greedyFill(int[][] available, int[] need, int[] target, Random random) {
        int[][] x = new int[SECTIONS][DIFFICULTIES];
        // Scarcest difficulty first (smallest slack between availability and need).
        List<Integer> difficultyOrder = new ArrayList<>();
        for (int d = 0; d < DIFFICULTIES; d++) {
            difficultyOrder.add(d);
        }
        difficultyOrder.sort(Comparator.comparingInt(d -> columnSum(available, d) - need[d]));

        for (int d : difficultyOrder) {
            for (int unit = 0; unit < need[d]; unit++) {
                List<Integer> candidates = new ArrayList<>();
                for (int s = 0; s < SECTIONS; s++) {
                    if (x[s][d] < available[s][d] && rowSum(x, s) < target[s]) {
                        candidates.add(s);
                    }
                }
                if (random != null) {
                    Collections.shuffle(candidates, random);
                }
                final int dd = d;
                // Fewest of this difficulty first; then the section with the most room left, so a section
                // that received an extra question of one difficulty receives fewer of the next.
                // List.sort is stable, so remaining ties keep the random order.
                candidates.sort(Comparator.<Integer>comparingInt(s -> x[s][dd])
                        .thenComparing(Comparator.<Integer>comparingInt(s -> target[s] - rowSum(x, s)).reversed()));
                boolean placed = false;
                for (int s : candidates) {
                    x[s][d]++;
                    if (remainingFeasible(available, need, target, x)) {
                        placed = true;
                        break;
                    }
                    x[s][d]--;
                }
                if (!placed) {
                    // Cannot happen when the initial max-flow check succeeded.
                    throw new AllocationException(List.of("Internal allocation error; please retry."));
                }
            }
        }
        return x;
    }

    private static boolean remainingFeasible(int[][] available, int[] need, int[] target, int[][] x) {
        int[][] restAvail = new int[SECTIONS][DIFFICULTIES];
        int[] restNeed = new int[DIFFICULTIES];
        int[] restTarget = new int[SECTIONS];
        int restTotal = 0;
        for (int d = 0; d < DIFFICULTIES; d++) {
            restNeed[d] = need[d] - columnSum(x, d);
            restTotal += restNeed[d];
        }
        for (int s = 0; s < SECTIONS; s++) {
            restTarget[s] = target[s] - rowSum(x, s);
            for (int d = 0; d < DIFFICULTIES; d++) {
                restAvail[s][d] = available[s][d] - x[s][d];
            }
        }
        return maxFlow(restAvail, restNeed, restTarget) == restTotal;
    }

    /**
     * Max flow on: source -> difficulty (need) -> section (available) -> sink (target).
     * Equal to the total exactly when an allocation satisfying all three constraints exists.
     */
    static int maxFlow(int[][] available, int[] need, int[] target) {
        int n = 2 + DIFFICULTIES + SECTIONS;
        int source = 0;
        int sink = n - 1;
        int[][] cap = new int[n][n];
        for (int d = 0; d < DIFFICULTIES; d++) {
            cap[source][1 + d] = Math.max(0, need[d]);
            for (int s = 0; s < SECTIONS; s++) {
                cap[1 + d][1 + DIFFICULTIES + s] = Math.max(0, available[s][d]);
            }
        }
        for (int s = 0; s < SECTIONS; s++) {
            cap[1 + DIFFICULTIES + s][sink] = Math.max(0, target[s]);
        }
        int flow = 0;
        while (true) {
            int[] parent = new int[n];
            Arrays.fill(parent, -1);
            parent[source] = source;
            Deque<Integer> queue = new ArrayDeque<>();
            queue.add(source);
            while (!queue.isEmpty() && parent[sink] == -1) {
                int u = queue.poll();
                for (int v = 0; v < n; v++) {
                    if (parent[v] == -1 && cap[u][v] > 0) {
                        parent[v] = u;
                        queue.add(v);
                    }
                }
            }
            if (parent[sink] == -1) {
                return flow;
            }
            int bottleneck = Integer.MAX_VALUE;
            for (int v = sink; v != source; v = parent[v]) {
                bottleneck = Math.min(bottleneck, cap[parent[v]][v]);
            }
            for (int v = sink; v != source; v = parent[v]) {
                cap[parent[v]][v] -= bottleneck;
                cap[v][parent[v]] += bottleneck;
            }
            flow += bottleneck;
        }
    }

    private static List<int[]> sectionPermutations(Random random) {
        List<int[]> all = new ArrayList<>();
        permute(new int[]{0, 1, 2}, 0, all);
        if (random != null) {
            Collections.shuffle(all, random);
        }
        return all;
    }

    private static void permute(int[] a, int k, List<int[]> out) {
        if (k == a.length) {
            out.add(a.clone());
            return;
        }
        for (int i = k; i < a.length; i++) {
            swap(a, i, k);
            permute(a, k + 1, out);
            swap(a, i, k);
        }
    }

    private static void swap(int[] a, int i, int j) {
        int t = a[i];
        a[i] = a[j];
        a[j] = t;
    }

    private static int columnSum(int[][] m, int d) {
        int sum = 0;
        for (int[] row : m) {
            sum += row[d];
        }
        return sum;
    }

    private static int rowSum(int[][] m, int s) {
        return Arrays.stream(m[s]).sum();
    }

    private static void validateShape(int[][] available, int[] need) {
        if (available.length != SECTIONS || need.length != DIFFICULTIES) {
            throw new IllegalArgumentException("Matrix shape must be [3][3] and need must have 3 entries");
        }
        for (int[] row : available) {
            if (row.length != DIFFICULTIES) {
                throw new IllegalArgumentException("Matrix shape must be [3][3]");
            }
            for (int v : row) {
                if (v < 0) {
                    throw new IllegalArgumentException("Negative availability");
                }
            }
        }
        for (int v : need) {
            if (v < 0) {
                throw new IllegalArgumentException("Negative requirement");
            }
        }
    }
}
