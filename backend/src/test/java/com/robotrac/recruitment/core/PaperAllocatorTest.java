package com.robotrac.recruitment.core;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaperAllocatorTest {

    /** Real availability of the supplied workbook minus flagged RBT-034 (Logical/Easy). */
    private static final int[][] WORKBOOK = {
            {14, 25, 11}, // Quantitative: easy, moderate, hard
            {13, 24, 12}, // Logical
            {14, 24, 12}  // Analytical
    };
    private static final int[] DEFAULT_MIX = {5, 15, 10};

    @RepeatedTest(50)
    void defaultPaperHasExactDifficultyMixAndTenPerSection() {
        int[][] plan = PaperAllocator.allocate(WORKBOOK, DEFAULT_MIX, true, new Random());
        int[] perDifficulty = new int[3];
        for (int s = 0; s < 3; s++) {
            int sectionTotal = 0;
            for (int d = 0; d < 3; d++) {
                assertThat(plan[s][d]).isBetween(0, WORKBOOK[s][d]);
                sectionTotal += plan[s][d];
                perDifficulty[d] += plan[s][d];
            }
            assertThat(sectionTotal).isEqualTo(10);
        }
        assertThat(perDifficulty).containsExactly(5, 15, 10);
    }

    @Test
    void difficultiesAreSpreadEvenlyAcrossSections() {
        int[][] plan = PaperAllocator.allocate(WORKBOOK, DEFAULT_MIX, true, null);
        for (int s = 0; s < 3; s++) {
            assertThat(plan[s][0]).isBetween(1, 2);  // easy 2/2/1
            assertThat(plan[s][1]).isEqualTo(5);     // moderate 5/5/5
            assertThat(plan[s][2]).isBetween(3, 4);  // hard 3/3/4
        }
    }

    @Test
    void reportsInsufficientDifficulty() {
        PaperAllocator.Feasibility f = PaperAllocator.check(WORKBOOK, new int[]{5, 15, 40}, true);
        assertThat(f.feasible()).isFalse();
        assertThat(f.problems()).anyMatch(p -> p.contains("HARD") && p.contains("need 40") && p.contains("available 35"));
    }

    @Test
    void reportsWhenSectionBalanceIsImpossible() {
        int[][] lopsided = {{0, 0, 20}, {0, 30, 0}, {10, 0, 0}};
        // 30 questions => 10 per section, but section 0 only has hard and we need just 5 hard.
        PaperAllocator.Feasibility f = PaperAllocator.check(lopsided, new int[]{10, 15, 5}, true);
        assertThat(f.feasible()).isFalse();
        assertThat(f.problems().get(0)).contains("cannot be split evenly");
        // Without balancing the same request is possible.
        assertThat(PaperAllocator.check(lopsided, new int[]{10, 15, 5}, false).feasible()).isTrue();
    }

    @Test
    void unbalancedModeOnlyNeedsDifficultyTotals() {
        int[][] plan = PaperAllocator.allocate(WORKBOOK, new int[]{3, 3, 3}, false, new Random(7));
        int total = 0;
        for (int[] row : plan) {
            for (int v : row) {
                total += v;
            }
        }
        assertThat(total).isEqualTo(9);
    }

    @Test
    void rejectsEmptyPaper() {
        assertThatThrownBy(() -> PaperAllocator.allocate(WORKBOOK, new int[]{0, 0, 0}, true, null))
                .isInstanceOf(PaperAllocator.AllocationException.class);
    }

    @Test
    void maxFlowMatchesSimpleCase() {
        assertThat(PaperAllocator.maxFlow(new int[][]{{1, 0, 0}, {0, 1, 0}, {0, 0, 1}}, new int[]{1, 1, 1}, new int[]{1, 1, 1}))
                .isEqualTo(3);
        assertThat(PaperAllocator.maxFlow(new int[][]{{1, 0, 0}, {1, 0, 0}, {0, 0, 1}}, new int[]{0, 1, 1}, new int[]{1, 1, 0}))
                .isZero();
    }
}
