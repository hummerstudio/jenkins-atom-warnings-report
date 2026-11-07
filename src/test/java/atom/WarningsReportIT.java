package atom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The warnings atom over real compiler/linter lines: javac, eslint and pylint shapes, the counts, the report and the
 * default gate. This is what {@code tools/smoke.sh} checked.
 */
class WarningsReportIT {

    private static final String LOG = "src/Main.java:12: warning: deprecated\n"
            + "src/Main.java:20: error: cannot find symbol\n"
            + "src/app.ts(5,7): warning - unused\n"
            + "src/mod.py:3: [C0114(missing-module-docstring), ] Missing\n";

    private Atom atom;

    @BeforeEach
    void setUp() throws Exception {
        atom = Atom.of();
    }

    @Test
    @DisplayName("javac/eslint/pylint lines are counted by severity and a report is written")
    void countsAndReports() throws Exception {
        AtomResult run = atom.file("logs/build.log", LOG)
                .input("{\"pattern\": \"logs/*.log\", \"maxErrors\": \"5\"}")
                .run();

        run.assertOk();
        assertEquals("1", run.field("errors"));
        assertEquals("3", run.field("warnings"));
        assertTrue(run.exists("out/warnings/index.html"), "the HTML report must be there");
    }

    @Test
    @DisplayName("one error fails the default gate")
    void failsOnAnErrorByDefault() throws Exception {
        AtomResult run = atom.file("build.log", "A.java:1: error: boom\n")
                .input("{\"pattern\": \"build.log\"}")
                .run();

        run.assertRefused();
        assertTrue(run.field("message").contains("error(s)"), run.field("message"));
    }
}
