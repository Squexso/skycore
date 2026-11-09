package dev.skycore.tracking.parse;

import java.util.regex.Pattern;

/**
 * Guards user-supplied regular expressions. Java regexes can backtrack catastrophically
 * (e.g. {@code (a+)+$}), which would freeze the game on every chat line. Two layers:
 * <ol>
 *   <li>a static check before activation (length, nested quantifiers, back-references) plus a timed
 *       trial against adversarial inputs, and</li>
 *   <li>a step budget at runtime: user rules match against a {@link Budgeted} char sequence that aborts
 *       once a match has inspected too many characters.</li>
 * </ol>
 */
public final class PatternSafety {
    public static final int MAX_LENGTH = 400;
    /** Character reads allowed for one match attempt on one line (lines are at most a few hundred chars). */
    public static final int STEP_BUDGET = 60_000;

    private PatternSafety() {}

    /** Thrown when a match exceeds its budget. */
    public static final class BudgetExceeded extends RuntimeException {
        public BudgetExceeded() { super("regex step budget exceeded", null, false, false); }
    }

    /** CharSequence that counts reads and aborts expensive matches. */
    public static final class Budgeted implements CharSequence {
        private final CharSequence s;
        private final int[] left;

        public Budgeted(CharSequence s, int budget) { this(s, new int[]{budget}); }

        private Budgeted(CharSequence s, int[] left) { this.s = s; this.left = left; }

        @Override public char charAt(int i) {
            if (--left[0] < 0) throw new BudgetExceeded();
            return s.charAt(i);
        }

        @Override public int length() { return s.length(); }

        @Override public CharSequence subSequence(int a, int b) { return new Budgeted(s.subSequence(a, b), left); }

        @Override public String toString() { return s.toString(); }
    }

    /** @return null if the regex is acceptable, otherwise a short reason (English, for logs and the status panel). */
    public static String check(String regex) {
        if (regex == null || regex.isBlank()) return "empty regex";
        if (regex.length() > MAX_LENGTH) return "regex longer than " + MAX_LENGTH + " characters";
        Pattern p;
        try {
            p = Pattern.compile(regex);
        } catch (Exception e) {
            String m = e.getMessage();
            return "invalid regex: " + (m == null ? "?" : m.lines().findFirst().orElse("?"));
        }
        if (regex.matches(".*\\\\[1-9].*") || regex.contains("\\k<")) return "back-references are not allowed";
        if (nestedQuantifier(regex)) return "nested quantifier (e.g. (a+)+) can freeze the game";
        // timed trial: worst-case style inputs must stay within budget
        String[] probes = {"a".repeat(64) + "!", "1".repeat(64) + "x", " ".repeat(64) + "#", "+1,234.5 ".repeat(12) + "?", "aA1 .,:+-/()".repeat(8)};
        for (String probe : probes) {
            try {
                p.matcher(new Budgeted(probe, STEP_BUDGET)).find();
            } catch (BudgetExceeded e) {
                return "too expensive to evaluate (catastrophic backtracking)";
            } catch (StackOverflowError e) {
                return "too deeply nested";
            }
        }
        return null;
    }

    /**
     * Detects a quantified group whose body itself contains an unbounded quantifier – the classic ReDoS shape,
     * e.g. {@code (a+)+}, {@code (\d*)*}, {@code (?:x|y+)*}. Possessive / atomic groups are allowed.
     */
    static boolean nestedQuantifier(String r) {
        // stack of "group contains an unbounded quantifier"
        java.util.ArrayDeque<boolean[]> stack = new java.util.ArrayDeque<>();
        boolean[] top = {false};
        boolean inClass = false;
        for (int i = 0; i < r.length(); i++) {
            char c = r.charAt(i);
            if (c == '\\') { i++; continue; }
            if (inClass) { if (c == ']') inClass = false; continue; }
            if (c == '[') { inClass = true; continue; }
            if (c == '(') {
                boolean atomic = r.startsWith("(?>", i);
                stack.push(top);
                top = new boolean[]{false, atomic};
                continue;
            }
            if (c == ')') {
                boolean inner = top[0];
                boolean atomic = top.length > 1 && top[1];
                top = stack.isEmpty() ? new boolean[]{false} : stack.pop();
                int j = i + 1;
                boolean quantified = j < r.length() && (r.charAt(j) == '*' || r.charAt(j) == '+' || unboundedBrace(r, j));
                boolean possessive = quantified && j + 1 < r.length() && r.charAt(j + 1) == '+';
                if (quantified && inner && !atomic && !possessive) return true;
                if (inner || quantified) top[0] = true;
                continue;
            }
            if ((c == '*' || c == '+') && i > 0) {
                boolean possessive = i + 1 < r.length() && r.charAt(i + 1) == '+';
                if (!possessive) top[0] = true;
            }
            if (c == '{' && unboundedBrace(r, i)) top[0] = true;
        }
        return false;
    }

    private static boolean unboundedBrace(String r, int i) {
        if (i >= r.length() || r.charAt(i) != '{') return false;
        int end = r.indexOf('}', i);
        if (end < 0) return false;
        String body = r.substring(i + 1, end);
        if (!body.matches("\\d+(,\\d*)?")) return false;
        if (body.endsWith(",")) return true;
        int comma = body.indexOf(',');
        return comma >= 0 && Integer.parseInt(body.substring(comma + 1)) > 50;
    }
}
