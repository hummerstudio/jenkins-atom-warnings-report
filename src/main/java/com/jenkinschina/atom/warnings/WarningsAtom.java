package com.jenkinschina.atom.warnings;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;
import com.jenkinschina.atom.sdk.AtomTask;
import com.jenkinschina.atom.sdk.ErrorType;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * warnings-ng as an atom: collect what the compilers and linters already printed and count it by severity.
 *
 * <p>Only the reading half of the plugin: no fingerprints, no new-versus-fixed comparison (that needs the previous
 * build's state, which is the runner's business), just the totals a build can act on.
 */
public class WarningsAtom implements AtomTask {

    /** file:line[:col]: (warning|error|note): message — javac, gcc, clang and most eslint-style outputs. */
    private static final Pattern COMPILER = Pattern.compile(
            "^(?<file>[^\\s].*?):(?<line>\\d+)(?::(?<col>\\d+))?[:\\s]\\s*(?<level>warning|error|note|info|fatal error):"
                    + "?\\s*(?<message>.+)$");
    /** path(line,col): severity message — tslint / eslint unix format. */
    private static final Pattern LINTER = Pattern.compile(
            "^(?<file>[^\\s].*?)\\((?<line>\\d+),(?<col>\\d+)\\):\\s*(?<level>error|warning)\\s*[-:]\\s*(?<message>.+)$");
    /** pylint: path:line: [C0114(missing-module-docstring), ] message */
    private static final Pattern PYLINT = Pattern.compile(
            "^(?<file>[^\\s].*?):(?<line>\\d+):\\s*\\[(?<code>[A-Z]\\d{4})(?:\\((?<name>[^)]*)\\))?[,\\]]?\\s*"
                    + "(?<message>.*)$");

    @Override
    public AtomResult run(AtomContext context) throws Exception {
        Path workspace = context.getWorkspace();
        String pattern = context.getParam("pattern", "**/build.log, **/*.log, **/warnings*.txt");
        String label = context.getParam("label", "Warnings");
        String outDir = context.getParam("outputDir", "out/warnings");
        int maxErrors = Math.max(0, context.getIntParam("maxErrors", 0));
        int maxWarnings = context.getIntParam("maxWarnings", -1);

        List<Path> files = match(workspace, pattern);
        List<Finding> findings = new ArrayList<>();
        int lines = 0;

        if (files.isEmpty() && context.hasBuildLog()) {
            lines += scan("<build log>", context.buildLog(), findings);
        }
        for (Path file : files) {
            lines += scan(workspace.relativize(file).toString(),
                    Files.readString(file, StandardCharsets.UTF_8), findings);
        }
        if (lines == 0) {
            return AtomResult.error(ErrorType.USER, 40001, "nothing to scan: no file matched " + pattern
                    + " and no build log was handed over");
        }

        Map<String, Integer> bySeverity = new LinkedHashMap<>();
        for (String severity : List.of("error", "warning", "note")) {
            bySeverity.put(severity, 0);
        }
        for (Finding finding : findings) {
            bySeverity.merge(finding.severity(), 1, Integer::sum);
        }
        int errors = bySeverity.get("error");
        int warnings = bySeverity.get("warning");

        Path dir = workspace.resolve(outDir);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("index.html"),
                render(label, pattern, lines, findings, errors, warnings), StandardCharsets.UTF_8);
        context.log("[warnings] " + findings.size() + " finding(s): " + errors + " error, " + warnings
                + " warning over " + lines + " line(s)");

        AtomResult result = AtomResult.success()
                .data("total", String.valueOf(findings.size()))
                .data("errors", String.valueOf(errors))
                .data("warnings", String.valueOf(warnings))
                .data("linesScanned", String.valueOf(lines))
                .report("report", label, dir.toString(), "index.html");

        boolean over = errors > maxErrors || (maxWarnings >= 0 && warnings > maxWarnings);
        if (over) {
            return AtomResult.failure(errors + " error(s) and " + warnings + " warning(s), limits are "
                    + maxErrors + " and " + (maxWarnings < 0 ? "none" : maxWarnings))
                    .data("errors", String.valueOf(errors))
                    .data("warnings", String.valueOf(warnings))
                    .report("report", label, dir.toString(), "index.html");
        }
        return result;
    }

    private static int scan(String source, String text, List<Finding> findings) {
        int count = 0;
        for (String raw : text.split("\n")) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            count++;
            Matcher compiler = COMPILER.matcher(line);
            if (compiler.matches()) {
                findings.add(new Finding(source, compiler.group("file"), compiler.group("line"),
                        normalise(compiler.group("level")), abbreviate(compiler.group("message"))));
                continue;
            }
            Matcher linter = LINTER.matcher(line);
            if (linter.matches()) {
                findings.add(new Finding(source, linter.group("file"), linter.group("line"),
                        normalise(linter.group("level")), abbreviate(linter.group("message"))));
                continue;
            }
            Matcher pylint = PYLINT.matcher(line);
            if (pylint.matches()) {
                findings.add(new Finding(source, pylint.group("file"), pylint.group("line"),
                        "warning", abbreviate(pylint.group("message"))));
            }
        }
        return count;
    }

    private static String normalise(String level) {
        String value = level.toLowerCase(Locale.ROOT);
        if (value.startsWith("fatal") || value.equals("error")) {
            return "error";
        }
        if (value.equals("warning")) {
            return "warning";
        }
        return "note";
    }

    private static String abbreviate(String value) {
        return value.length() > 160 ? value.substring(0, 160) + "…" : value;
    }

    private static List<Path> match(Path workspace, String pattern) throws Exception {
        List<PathMatcher> matchers = new ArrayList<>();
        for (String part : pattern.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + trimmed));
            }
        }
        List<Path> files = new ArrayList<>();
        try (var walk = Files.walk(workspace, 12)) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> matchers.stream().anyMatch(m -> m.matches(workspace.relativize(path))
                            || m.matches(java.nio.file.Path.of("_").resolve(workspace.relativize(path)))
                            || m.matches(path.getFileName())))
                    .sorted()
                    .limit(200)
                    .forEach(files::add);
        }
        return files;
    }

    private static String render(String label, String pattern, int lines, List<Finding> findings, int errors,
                                 int warnings) {
        StringBuilder html = new StringBuilder("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">");
        html.append("<title>").append(label).append("</title><style>")
                .append("body{font-family:-apple-system,'Segoe UI',sans-serif;margin:0;padding:28px;background:#f6f7f9}")
                .append("h1{font-size:22px;margin:0 0 4px}p.meta{color:#6b7280;font-size:13px;margin:0 0 18px}")
                .append(".cards{display:flex;gap:10px;margin-bottom:20px}")
                .append(".card{background:#fff;border:1px solid #e5e7eb;border-radius:10px;padding:12px 16px;min-width:100px}")
                .append(".card b{display:block;font-size:22px}.card span{font-size:12px;color:#6b7280}")
                .append("table{width:100%;border-collapse:collapse;background:#fff;border:1px solid #e5e7eb;font-size:13px}")
                .append("th,td{text-align:left;padding:8px 12px;border-bottom:1px solid #eef0f3;vertical-align:top}")
                .append("th{background:#fafbfc;color:#6b7280}code{font-family:ui-monospace,Menlo,monospace}")
                .append("</style></head><body>");
        html.append("<h1>").append(label).append("</h1><p class=\"meta\">").append(findings.size())
                .append(" finding(s) over ").append(lines).append(" line(s) · <code>").append(pattern)
                .append("</code></p><div class=\"cards\">");
        html.append("<div class=\"card\"><b>").append(errors).append("</b><span>error</span></div>");
        html.append("<div class=\"card\"><b>").append(warnings).append("</b><span>warning</span></div></div>");
        html.append("<table><tr><th>File</th><th>Severity</th><th>Message</th><th>Source</th></tr>");
        for (Finding finding : findings.subList(0, Math.min(findings.size(), 300))) {
            html.append("<tr><td><code>").append(finding.file()).append(':').append(finding.line())
                    .append("</code></td><td>").append(finding.severity()).append("</td><td>")
                    .append(finding.message()).append("</td><td>").append(finding.source()).append("</td></tr>");
        }
        return html.append("</table></body></html>").toString();
    }

    private record Finding(String source, String file, String line, String severity, String message) {}
}
