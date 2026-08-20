package com.cvijeticc.diffreview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cvijeticc.diffreview.diff.DiffFile;
import com.cvijeticc.diffreview.diff.DiffLine;
import com.cvijeticc.diffreview.diff.DiffParseException;
import com.cvijeticc.diffreview.diff.UnifiedDiffParser;
import java.util.List;
import org.junit.jupiter.api.Test;

class UnifiedDiffParserTest {

    private static String diff(String... lines) {
        return String.join("\n", lines) + "\n";
    }

    @Test
    void rejectsPlainTextAndEmptyInput() {
        assertThatThrownBy(() -> UnifiedDiffParser.parse("hello world"))
                .isInstanceOf(DiffParseException.class);
        assertThatThrownBy(() -> UnifiedDiffParser.parse(""))
                .isInstanceOf(DiffParseException.class);
        assertThatThrownBy(() -> UnifiedDiffParser.parse("   \n  \n"))
                .isInstanceOf(DiffParseException.class);
    }

    @Test
    void rejectsHunkWithoutFileHeader() {
        assertThatThrownBy(() -> UnifiedDiffParser.parse(diff("@@ -1 +1 @@", "+x")))
                .isInstanceOf(DiffParseException.class);
    }

    @Test
    void overcountedHunkKeepsWhatIsActuallyThereInsteadOfFailingTheDiff() {
        // The header promises 3 new lines and delivers 2. Rejecting the whole
        // diff over a miscounted header loses findings that are plainly there;
        // the content is the truth and the count is only a hint.
        List<DiffFile> files = UnifiedDiffParser.parse(diff(
                "--- a/x.js", "+++ b/x.js", "@@ -1,2 +1,3 @@", " one", "+two"));
        assertThat(files).hasSize(1);
        assertThat(files.get(0).hunks().get(0).lines())
                .extracting(DiffLine::content).containsExactly("one", "two");
    }

    @Test
    void parsesPlainUnifiedDiffWithoutGitPrefixes() {
        List<DiffFile> files = UnifiedDiffParser.parse(diff(
                "--- src/x.js", "+++ src/x.js", "@@ -1 +1 @@", "-old", "+new"));
        assertThat(files).hasSize(1);
        assertThat(files.get(0).path()).isEqualTo("src/x.js");
        DiffLine added = files.get(0).hunks().get(0).lines().get(1);
        assertThat(added.type()).isEqualTo('+');
        assertThat(added.content()).isEqualTo("new");
        assertThat(added.newLineNumber()).isEqualTo(1);
    }

    @Test
    void stripsGitPathPrefixes() {
        List<DiffFile> files = UnifiedDiffParser.parse(diff(
                "diff --git a/src/db.ts b/src/db.ts",
                "index 111..222 100644",
                "--- a/src/db.ts",
                "+++ b/src/db.ts",
                "@@ -40,1 +40,2 @@",
                " keep",
                "+added"));
        assertThat(files.get(0).path()).isEqualTo("src/db.ts");
        assertThat(files.get(0).hunks().get(0).lines().get(1).newLineNumber()).isEqualTo(41);
    }

    @Test
    void newFileUsesNewPathAndDeletedFileUsesOldPath() {
        List<DiffFile> created = UnifiedDiffParser.parse(diff(
                "--- /dev/null", "+++ b/new.js", "@@ -0,0 +1,2 @@", "+a", "+b"));
        assertThat(created.get(0).path()).isEqualTo("new.js");
        assertThat(created.get(0).hunks().get(0).lines())
                .extracting(DiffLine::newLineNumber).containsExactly(1, 2);

        List<DiffFile> deleted = UnifiedDiffParser.parse(diff(
                "--- a/old.js", "+++ /dev/null", "@@ -1,2 +0,0 @@", "-a", "-b"));
        assertThat(deleted.get(0).path()).isEqualTo("old.js");
        assertThat(deleted.get(0).hunks().get(0).lines())
                .allSatisfy(l -> assertThat(l.type()).isEqualTo('-'));
    }

    @Test
    void handlesCrlfLineEndings() {
        String crlf = "--- a/x.js\r\n+++ b/x.js\r\n@@ -0,0 +1,1 @@\r\n+console.log(1);\r\n";
        List<DiffFile> files = UnifiedDiffParser.parse(crlf);
        assertThat(files.get(0).hunks().get(0).lines().get(0).content()).isEqualTo("console.log(1);");
    }

    @Test
    void handlesTimestampAfterTabInHeaders() {
        List<DiffFile> files = UnifiedDiffParser.parse(diff(
                "--- a/x.js\t2026-01-01 00:00:00", "+++ b/x.js\t2026-01-02 00:00:00",
                "@@ -1 +1 @@", "-a", "+b"));
        assertThat(files.get(0).path()).isEqualTo("x.js");
    }

    @Test
    void removedLineStartingWithDashesStaysInsideHunk() {
        // The removed line renders as "--- x" and must not be read as a file header.
        List<DiffFile> files = UnifiedDiffParser.parse(diff(
                "--- a/x.js", "+++ b/x.js", "@@ -1,2 +1,1 @@", " a", "--- x"));
        assertThat(files).hasSize(1);
        assertThat(files.get(0).hunks().get(0).lines().get(1).type()).isEqualTo('-');
        assertThat(files.get(0).hunks().get(0).lines().get(1).content()).isEqualTo("-- x");
    }

    @Test
    void parsesMultipleFilesWithRawSections() {
        List<DiffFile> files = UnifiedDiffParser.parse(diff(
                "--- a/one.js", "+++ b/one.js", "@@ -0,0 +1,1 @@", "+first",
                "--- a/two.js", "+++ b/two.js", "@@ -0,0 +1,1 @@", "+second"));
        assertThat(files).extracting(DiffFile::path).containsExactly("one.js", "two.js");
        assertThat(files.get(0).rawText()).contains("+first").doesNotContain("+second");
        assertThat(files.get(1).rawText()).contains("+second");
        assertThat(files.get(0).byteSize()).isGreaterThan(0);
    }

    @Test
    void ignoresNoNewlineMarker() {
        List<DiffFile> files = UnifiedDiffParser.parse(diff(
                "--- a/x.js", "+++ b/x.js", "@@ -1 +1 @@", "-a", "+b",
                "\\ No newline at end of file"));
        assertThat(files.get(0).hunks().get(0).lines()).hasSize(2);
    }

    // --- Tolerance for headers a hand-written or generated diff gets wrong. ---
    // Every one of these used to either lose findings silently or fail the
    // whole submission with 422; the counts stay authoritative while they run,
    // so none of this weakens the header-lookalike protection above.

    @Test
    void undercountedHunkStillYieldsEveryAddedLine() {
        // "@@ -0,0 +1,1 @@" followed by three added lines. The old parser left
        // the loop after the first one and swallowed the rest as junk: the job
        // finished "done" and two findings had quietly disappeared.
        List<DiffFile> files = UnifiedDiffParser.parse(diff(
                "--- a/src/db.ts", "+++ b/src/db.ts", "@@ -0,0 +1,1 @@",
                "+console.log(1);", "+// TODO fix", "+eval(x);"));
        assertThat(files).hasSize(1);
        assertThat(files.get(0).hunks().get(0).lines())
                .extracting(DiffLine::content)
                .containsExactly("console.log(1);", "// TODO fix", "eval(x);");
        assertThat(files.get(0).hunks().get(0).lines())
                .extracting(DiffLine::newLineNumber).containsExactly(1, 2, 3);
    }

    @Test
    void undercountedHunkStopsAtTheNextFileSection() {
        List<DiffFile> files = UnifiedDiffParser.parse(diff(
                "--- a/one.js", "+++ b/one.js", "@@ -0,0 +1,1 @@", "+a", "+b",
                "--- a/two.js", "+++ b/two.js", "@@ -0,0 +1,1 @@", "+c"));
        assertThat(files).extracting(DiffFile::path).containsExactly("one.js", "two.js");
        assertThat(files.get(0).hunks().get(0).lines())
                .extracting(DiffLine::content).containsExactly("a", "b");
        assertThat(files.get(1).hunks().get(0).lines())
                .extracting(DiffLine::content).containsExactly("c");
    }

    @Test
    void overcountedHunkEndsCleanlyAtTheNextHunkHeader() {
        List<DiffFile> files = UnifiedDiffParser.parse(diff(
                "--- a/x.js", "+++ b/x.js",
                "@@ -0,0 +1,5 @@", "+a", "+b",
                "@@ -10,0 +20,1 @@", "+c"));
        assertThat(files).hasSize(1);
        assertThat(files.get(0).hunks()).hasSize(2);
        assertThat(files.get(0).hunks().get(0).lines())
                .extracting(DiffLine::content).containsExactly("a", "b");
        assertThat(files.get(0).hunks().get(1).lines().get(0).newLineNumber()).isEqualTo(20);
    }

    @Test
    void gitHeaderSuppliesThePathWhenTheMinusPlusPairIsMissing() {
        // A generated diff that keeps "diff --git" but drops ---/+++ is common
        // enough that failing it costs more than trusting the git header.
        List<DiffFile> files = UnifiedDiffParser.parse(diff(
                "diff --git a/src/db.ts b/src/db.ts",
                "index 111..222 100644",
                "@@ -0,0 +1,1 @@",
                "+eval(userInput);"));
        assertThat(files).hasSize(1);
        assertThat(files.get(0).path()).isEqualTo("src/db.ts");
        assertThat(files.get(0).hunks().get(0).lines().get(0).newLineNumber()).isEqualTo(1);
    }

    @Test
    void aLonePlusHeaderIsAcceptedAsTheNewPath() {
        List<DiffFile> files = UnifiedDiffParser.parse(diff(
                "+++ b/src/db.ts", "@@ -0,0 +1,1 @@", "+eval(x);"));
        assertThat(files).hasSize(1);
        assertThat(files.get(0).path()).isEqualTo("src/db.ts");
    }

    @Test
    void theMinusPlusPairStillWinsOverTheGitHeader() {
        List<DiffFile> files = UnifiedDiffParser.parse(diff(
                "diff --git a/stale/path.ts b/stale/path.ts",
                "--- a/real/path.ts", "+++ b/real/path.ts",
                "@@ -0,0 +1,1 @@", "+x"));
        assertThat(files.get(0).path()).isEqualTo("real/path.ts");
    }

    @Test
    void toleranceDoesNotTurnPlainTextIntoADiff() {
        // The relaxations above only apply once a file section and a hunk
        // header exist; anything short of that is still not a unified diff.
        assertThatThrownBy(() -> UnifiedDiffParser.parse(diff("just some prose", "with two lines")))
                .isInstanceOf(DiffParseException.class);
        assertThatThrownBy(() -> UnifiedDiffParser.parse(diff("--- a/x.js", "+++ b/x.js")))
                .isInstanceOf(DiffParseException.class);
        assertThatThrownBy(() -> UnifiedDiffParser.parse(diff("diff --git a/x.js b/x.js", "index 1..2")))
                .isInstanceOf(DiffParseException.class);
    }
}
