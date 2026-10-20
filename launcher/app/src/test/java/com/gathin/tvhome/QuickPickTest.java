package com.gathin.tvhome;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.HashSet;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class QuickPickTest {

    private static Predicate<String> installed(String... pkgs) {
        Set<String> present = new HashSet<>(Arrays.asList(pkgs));
        return present::contains;
    }

    @Test public void usesQuickAppsWhenPresentAndKeepsOrder() {
        List<String> picked = QuickPick.choose(
                Arrays.asList("a", "b", "c"), Arrays.asList("x", "y"),
                installed("a", "b", "c", "x", "y"));
        assertEquals(Arrays.asList("a", "b", "c"), picked);
    }

    @Test public void fallsBackToDockWhenQuickAppsEmpty() {
        List<String> picked = QuickPick.choose(
                Collections.emptyList(), Arrays.asList("x", "y"), installed("x", "y"));
        assertEquals(Arrays.asList("x", "y"), picked);
    }

    @Test public void dropsAppsThatAreNotInstalled() {
        List<String> picked = QuickPick.choose(
                Arrays.asList("a", "gone", "c"), null, installed("a", "c"));
        assertEquals(Arrays.asList("a", "c"), picked);
    }

    @Test public void capsAtFour() {
        List<String> picked = QuickPick.choose(
                Arrays.asList("a", "b", "c", "d", "e"), null, installed("a", "b", "c", "d", "e"));
        assertEquals(Arrays.asList("a", "b", "c", "d"), picked);
    }

    @Test public void skipsBlanksAndDuplicates() {
        List<String> picked = QuickPick.choose(
                Arrays.asList("a", " ", "a", "", "b"), null, installed("a", "b"));
        assertEquals(Arrays.asList("a", "b"), picked);
    }

    @Test public void emptyWhenNothingInstalled() {
        assertTrue(QuickPick.choose(Arrays.asList("a", "b"), Arrays.asList("c"), installed()).isEmpty());
    }
}
