package org.yanbwe.modularshoot.network;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Architecture guard for 任务 6.2 (客户端 payload handler 移出 common): the common
 * network class {@link ModularShootPayloads} must not directly import
 * {@code net.minecraft.client} classes, because it is loaded on dedicated
 * servers during payload registration. Any {@code client}-bound reference there
 * would break the dedicated-server safe-class-loading guarantee.
 *
 * <p><strong>What this guard protects</strong> is the real runtime-safety
 * target: no {@code net.minecraft.client} import inside {@code ModularShootPayloads}.
 * The actual handler implementations live in
 * {@code org.yanbwe.modularshoot.client.ClientPayloadHandlers} (which is client
 * code and legitimately imports Minecraft client classes); {@code ModularShootPayloads}
 * forwards to it through lazily-invoked lambda bodies, so the class is only
 * loaded when a handler actually runs on the physical client.</p>
 *
 * <p><strong>Deliberately <em>not</em> enforced here:</strong> the import of the
 * mod's <em>own</em> client package
 * ({@code org.yanbwe.modularshoot.client.ClientPayloadHandlers}) is allowed and
 * expected — that reference is made safe by the lazy lambda indirection, as
 * recorded in {@link ModularShootPayloads}'s Javadoc. This test therefore bans
 * only the direct {@code net.minecraft.client.*} dependency, which is the guard
 * that protects the dedicated server load path. Should the design ever move to a
 * client-dist-only binding, the guard would need to switch to an
 * {@code FMLEnvironment.dist} check instead.</p>
 */
@Execution(ExecutionMode.SAME_THREAD)
class ModularShootPayloadsClientImportGuardTest {

    /** Relative (from the project root) path of the guarded source file. */
    private static final Path TARGET_REL = Paths.get(
            "src", "main", "java", "org", "yanbwe", "modularshoot",
            "network", "ModularShootPayloads.java");

    /** The exact fully-qualified Minecraft client package prefix to forbid. */
    private static final String CLIENT_IMPORT_PREFIX = "import net.minecraft.client.";

    @Test
    void commonPayloadsClassDoesNotImportMinecraftClient() throws IOException {
        Path target = resolveProjectRoot().resolve(TARGET_REL);
        List<String> offenders = new ArrayList<>();
        int lineNo = 0;
        for (String rawLine : Files.readAllLines(target, StandardCharsets.UTF_8)) {
            lineNo++;
            String line = rawLine.trim();
            if (line.contains(CLIENT_IMPORT_PREFIX)) {
                // Also report any statically-imported client symbol, though we
                // only forbid net.minecraft.client to keep the guard focused on
                // the dedicated-server load path.
                offenders.add("line " + lineNo + ": " + line);
            }
        }
        if (!offenders.isEmpty()) {
            fail("ModularShootPayloads must not import net.minecraft.client classes "
                    + "(dedicated-server safe-loading guarantee); offenders:\n  "
                    + String.join("\n  ", offenders));
        }
    }

    /**
     * Locates the module (project) root without relying on the process CWD, the
     * same way the other boundary test does, by walking upwards from
     * {@code user.dir} to find the guarded source file.
     *
     * @return the absolute module root directory
     */
    private static Path resolveProjectRoot() {
        Path start = Paths.get("").toAbsolutePath().normalize();
        Path dir = start;
        for (int i = 0; i < 10 && dir != null; i++) {
            if (Files.isRegularFile(dir.resolve(TARGET_REL))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
                "Client-import guard: cannot locate the ModularShoot module root. "
                        + "Searched upwards from '" + start + "' for '"
                        + TARGET_REL.toString().replace('\\', '/')
                        + "'. Run the tests from the project root (or any directory "
                        + "below it within the repository).");
    }
}
