package org.yanbwe.modularshoot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Architecture boundary test for Task 6.1: the internal framework classes must
 * not depend on (import or invoke) the public {@link ModularShootAPI} facade.
 *
 * <p>ModularShootAPI is the <em>outward</em> API surface for add-on mods. The
 * dual-channel gun/plugin recognition logic it used to own (`isGun`,
 * `resolveGunId`, `resolvePluginId`) lives in the dedicated internal utility
 * {@code org.yanbwe.modularshoot.util.GunRecognition}, shared by the facade and
 * the internal services, so the dependency graph stays one-directional:
 * internal framework &rarr; util &rarr; facade (never framework &rarr; facade).</p>
 *
 * <p>Rather than hard-coding a brittle list of six service files, this test
 * statically scans <strong>every</strong> common (non-client / non-command)
 * {@code .java} file under {@code src/main/java/org/yanbwe/modularshoot/} and
 * asserts none of them imports or references {@code ModularShootAPI}. It is a
 * regression guard, not a compiler: it reads the checked-in source text so a
 * future re-introduction of the coupling fails loudly.</p>
 *
 * <p><strong>Scope exclusions (documented):</strong></p>
 * <ul>
 *   <li>{@code ModularShootAPI.java} itself is the facade under test.</li>
 *   <li>Everything under a {@code client} or {@code command} package (including
 *       {@code mixin/client}) is the outward-facing peripheral layer and is out
 *       of scope for 6.1. Those classes legitimately speak to the facade.</li>
 *   <li>{@code network/ModularShootPayloads.java} still calls
 *       {@code ModularShootAPI.isGun}; it is deferred to Task 6.2 and excluded
 *       here deliberately (not silently dropped) so the guard does not fail
 *       until that task lands.</li>
 * </ul>
 */
@Execution(ExecutionMode.SAME_THREAD)
class ModularShootAPIBoundaryTest {

    /** Relative (from the project root) path of the framework source package. */
    private static final Path PKG_REL = Paths.get(
            "src", "main", "java", "org", "yanbwe", "modularshoot");

    /** Marker file used to locate the module root regardless of the CWD. */
    private static final Path ROOT_MARKER_REL = PKG_REL.resolve("ModularShootAPI.java");

    /** The fully-qualified facade class name (matches the import form). */
    private static final String API_FQN = "org.yanbwe.modularshoot.ModularShootAPI";

    @Test
    void internalFrameworkClassesDoNotReferenceModularShootAPI() throws IOException {
        Path pkgRoot = resolveProjectRoot().resolve(PKG_REL);
        List<String> offenders = new ArrayList<>();
        for (Path file : collectCommonFrameworkFiles(pkgRoot)) {
            if (containsApiReference(file)) {
                offenders.add(pkgRoot.relativize(file).toString().replace('\\', '/'));
            }
        }
        if (!offenders.isEmpty()) {
            fail("Internal framework classes must not reference ModularShootAPI; offenders: "
                    + offenders);
        }
    }

    /**
     * Walks the framework source package and returns every common (non-client /
     * non-command, non-facade, not-yet-migrated) {@code .java} file whose
     * boundary is checked.
     *
     * @param pkgRoot the {@code org/yanbwe/modularshoot} source directory
     * @return the list of source files to scan
     * @throws IOException when the source tree cannot be walked
     */
    private static List<Path> collectCommonFrameworkFiles(Path pkgRoot) throws IOException {
        if (!Files.isDirectory(pkgRoot)) {
            throw new IllegalStateException(
                    "Boundary scan: framework source package not found at " + pkgRoot.toAbsolutePath());
        }
        try (Stream<Path> stream = Files.walk(pkgRoot)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .filter(p -> !isExcluded(pkgRoot, p))
                    .collect(Collectors.toList());
        }
    }

    /**
     * Decides whether a source file is outside the 6.1 one-directionalisation
     * scope and may keep referencing the facade.
     *
     * @param pkgRoot the framework source directory (for relative paths)
     * @param file    the source file under consideration
     * @return {@code true} when the file should be skipped by the boundary check
     */
    private static boolean isExcluded(Path pkgRoot, Path file) {
        String rel = pkgRoot.relativize(file).toString().replace('\\', '/');
        // The facade itself is the allowed holder of its own name.
        if (rel.equals("ModularShootAPI.java")) {
            return true;
        }
        // Client-side and command-side packages (incl. mixin/client) are
        // outward-facing peripheral layers, out of scope for 6.1.
        if (rel.contains("/client/") || rel.startsWith("client/")) {
            return true;
        }
        if (rel.contains("/command/") || rel.startsWith("command/")) {
            return true;
        }
        // network/ModularShootPayloads still calls ModularShootAPI.isGun;
        // deferred to Task 6.2. Excluded explicitly (documented), not silently.
        if (rel.equals("network/ModularShootPayloads.java")) {
            return true;
        }
        return false;
    }

    /**
     * Returns {@code true} when the source file directly references the facade
     * class (import statement, or any {@code ModularShootAPI.<method>} usage).
     * Comment-only lines are ignored.
     */
    private static boolean containsApiReference(Path file) throws IOException {
        try {
            for (String rawLine : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String line = rawLine.trim();
                if (line.isEmpty()
                        || line.startsWith("//")
                        || line.startsWith("*")
                        || line.startsWith("/*")) {
                    continue;
                }
                if (line.contains(API_FQN) || line.contains("ModularShootAPI.")) {
                    return true;
                }
            }
        } catch (IOException e) {
            throw new IOException("Failed to read boundary-scan source " + file.toAbsolutePath(), e);
        }
        return false;
    }

    /**
     * Locates the module (project) root without relying on the process CWD by
     * walking upwards from {@code user.dir} to find the marker file
     * {@code src/main/java/org/yanbwe/modularshoot/ModularShootAPI.java}. Raises
     * a readable diagnostic instead of a bare path exception when the root
     * cannot be found.
     *
     * @return the absolute module root directory
     */
    private static Path resolveProjectRoot() {
        Path start = Paths.get("").toAbsolutePath().normalize();
        Path dir = start;
        for (int i = 0; i < 10 && dir != null; i++) {
            if (Files.isRegularFile(dir.resolve(ROOT_MARKER_REL))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
                "Boundary scan: cannot locate the ModularShoot module root. "
                        + "Searched upwards from '" + start + "' for '"
                        + ROOT_MARKER_REL.toString().replace('\\', '/')
                        + "'. Run the tests from the project root (or any directory "
                        + "below it within the repository).");
    }
}
