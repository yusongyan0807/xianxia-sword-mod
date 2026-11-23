package com.example.xianxia.e2e;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.ResourceLocationException;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.lang.model.SourceVersion;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Headless JUnit 5 opaque-box E2E compliance suite for xianxia_placeholder (NeoForge 1.21.1).
 * Covers F1, F2, F3, F7, F8 static/asset/bytecode/JAR contracts plus Tier 3 & Tier 4 compliance checks.
 */
public class E2EComplianceTests {

    private static final String MOD_ID = "xianxia_placeholder";
    private static final String ITEM_ID = "qingfeng_flying_sword";
    private static final String TAB_ID = "xianxia_tab";
    private static final String GROUP_ID = "com.example.xianxia";
    private static final String E2E_INTERNAL_PREFIX = "com/example/xianxia/e2e/";
    private static final Pattern UNRESOLVED_TOKEN_PATTERN = Pattern.compile("\\$\\{[A-Za-z0-9_.-]+\\}");
    private static final Pattern CJK_PATTERN = Pattern.compile("[\\u4E00-\\u9FFF]");

    // =========================================================================
    // Project Root & Discovery Helpers
    // =========================================================================

    private static Path resolveProjectRoot() {
        Path cur = Path.of("").toAbsolutePath().normalize();
        while (cur != null) {
            if (Files.isRegularFile(cur.resolve("build.gradle"))
                    && Files.isRegularFile(cur.resolve("settings.gradle"))) {
                return cur;
            }
            cur = cur.getParent();
        }
        fail("Could not locate project root (build.gradle + settings.gradle) walking up from "
                + Path.of("").toAbsolutePath());
        return null;
    }

    private static Properties loadGradleProperties(Path root) throws IOException {
        Path propsPath = root.resolve("gradle.properties");
        assertTrue(Files.isRegularFile(propsPath), "gradle.properties must exist at " + propsPath);
        Properties props = new Properties();
        String raw = E2EComplianceValidators.decodeStrictUtf8(Files.readAllBytes(propsPath), "gradle.properties");
        props.load(new StringReader(raw));
        return props;
    }

    private static String readUtf8File(Path path, String label) throws IOException {
        assertTrue(Files.isRegularFile(path), label + " must exist at " + path);
        return E2EComplianceValidators.decodeStrictUtf8(Files.readAllBytes(path), label);
    }

    private static Path findProductionJar(Path root) throws IOException {
        Path libsDir = root.resolve("build/libs");
        assertTrue(Files.isDirectory(libsDir),
                "build/libs/ directory not found at " + libsDir
                        + ". Run './gradlew.bat build --no-daemon' first to produce the clean production JAR.");
        List<Path> candidates;
        try (Stream<Path> stream = Files.list(libsDir)) {
            candidates = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith(MOD_ID + "-")
                                && name.endsWith(".jar")
                                && !name.endsWith("-sources.jar")
                                && !name.endsWith("-javadoc.jar");
                    })
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        }
        assertFalse(candidates.isEmpty(),
                "No primary production JAR (" + MOD_ID + "-*.jar) found in " + libsDir
                        + ". Run './gradlew.bat build --no-daemon' first.");
        return candidates.get(0);
    }

    private static List<E2EClassfileInspector.ClassInfo> loadMainClasses(Path root) throws IOException {
        Path classesDir = root.resolve("build/classes/java/main");
        assertTrue(Files.isDirectory(classesDir), "Compiled main classes directory not found: " + classesDir);
        List<E2EClassfileInspector.ClassInfo> classes = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(classesDir)) {
            List<Path> classFiles = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".class"))
                    .toList();
            for (Path cf : classFiles) {
                String rel = classesDir.relativize(cf).toString().replace('\\', '/');
                if (rel.startsWith(E2E_INTERNAL_PREFIX)) {
                    continue;
                }
                classes.add(E2EClassfileInspector.parse(Files.readAllBytes(cf)));
            }
        }
        assertFalse(classes.isEmpty(), "Expected compiled production classes under " + classesDir);
        return classes;
    }

    private static List<E2EClassfileInspector.ClassInfo> loadJarClasses(Path jarPath) throws IOException {
        List<E2EClassfileInspector.ClassInfo> classes = new ArrayList<>();
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (!entry.isDirectory() && entry.getName().endsWith(".class")) {
                    try (InputStream in = jar.getInputStream(entry)) {
                        classes.add(E2EClassfileInspector.parse(in.readAllBytes()));
                    }
                }
            }
        }
        return classes;
    }

    private static E2EClassfileInspector.ClassInfo findModEntrypoint(List<E2EClassfileInspector.ClassInfo> classes) {
        for (E2EClassfileInspector.ClassInfo ci : classes) {
            E2EClassfileInspector.AnnotationInfo modAnn = ci.findAnnotation("Lnet/neoforged/fml/common/Mod;");
            if (modAnn != null && MOD_ID.equals(modAnn.getString("value"))) {
                return ci;
            }
        }
        fail("No compiled class annotated with @Mod(\"" + MOD_ID + "\") found");
        return null;
    }

    private static E2EClassfileInspector.ClassInfo findClientSubscriber(List<E2EClassfileInspector.ClassInfo> classes) {
        for (E2EClassfileInspector.ClassInfo ci : classes) {
            E2EClassfileInspector.AnnotationInfo ann = ci.findAnnotation("Lnet/neoforged/fml/common/EventBusSubscriber;");
            if (ann != null) {
                String modid = ann.getString("modid");
                boolean modMatches = modid == null || MOD_ID.equals(modid);
                boolean isClientDist = ann.getEnumValues("value")
                        .contains("Lnet/neoforged/api/distmarker/Dist;#CLIENT");
                if (modMatches && isClientDist) {
                    return ci;
                }
            }
        }
        fail("No compiled @EventBusSubscriber(Dist.CLIENT) listener class found for modid=" + MOD_ID);
        return null;
    }

    private static E2EClassfileInspector.ClassInfo findRenderLayerClass(List<E2EClassfileInspector.ClassInfo> classes) {
        for (E2EClassfileInspector.ClassInfo ci : classes) {
            if ("net/minecraft/client/renderer/entity/layers/RenderLayer".equals(ci.superName())) {
                return ci;
            }
        }
        fail("No compiled RenderLayer subclass found (superclass net/minecraft/client/renderer/entity/layers/RenderLayer)");
        return null;
    }

    private static boolean isDirectClientOnlyClass(E2EClassfileInspector.ClassInfo ci) {
        if (ci.internalName().contains("/client/")) {
            return true;
        }
        if ("net/minecraft/client/renderer/entity/layers/RenderLayer".equals(ci.superName())) {
            return true;
        }
        E2EClassfileInspector.AnnotationInfo ann = ci.findAnnotation("Lnet/neoforged/fml/common/EventBusSubscriber;");
        return ann != null && ann.getEnumValues("value").contains("Lnet/neoforged/api/distmarker/Dist;#CLIENT");
    }

    private static Set<String> collectClientOnlyInternalNames(List<E2EClassfileInspector.ClassInfo> classes) {
        Set<String> clientNames = new HashSet<>();
        List<E2EClassfileInspector.ClassInfo> sorted = classes.stream()
                .sorted(Comparator.comparingInt(c -> c.internalName().length()))
                .toList();
        for (E2EClassfileInspector.ClassInfo ci : sorted) {
            if (isDirectClientOnlyClass(ci)) {
                clientNames.add(ci.internalName());
                continue;
            }
            String name = ci.internalName();
            int dollar = name.lastIndexOf('$');
            while (dollar > 0) {
                String outer = name.substring(0, dollar);
                if (clientNames.contains(outer)) {
                    clientNames.add(ci.internalName());
                    break;
                }
                dollar = outer.lastIndexOf('$');
            }
        }
        return clientNames;
    }

    private static String readExpandedModsToml(Path root) throws IOException {
        Path generated = root.resolve("build/generated/sources/modMetadata/META-INF/neoforge.mods.toml");
        if (Files.isRegularFile(generated)) {
            return readUtf8File(generated, "generated neoforge.mods.toml");
        }
        try (InputStream in = E2EComplianceTests.class.getClassLoader().getResourceAsStream("META-INF/neoforge.mods.toml")) {
            assertNotNull(in, "Expanded META-INF/neoforge.mods.toml must exist in build/generated or classpath");
            return E2EComplianceValidators.decodeStrictUtf8(in.readAllBytes(), "classpath neoforge.mods.toml");
        }
    }

    private static Map<String, BufferedImage> buildTextureMap(Path root) throws IOException {
        Path texRoot = root.resolve("src/main/resources/assets/" + MOD_ID + "/textures");
        Map<String, BufferedImage> cache = new HashMap<>();
        if (!Files.isDirectory(texRoot)) {
            return cache;
        }
        try (Stream<Path> walk = Files.walk(texRoot)) {
            for (Path p : walk.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().endsWith(".png")).toList()) {
                String rel = texRoot.relativize(p).toString().replace('\\', '/');
                String noExt = rel.substring(0, rel.length() - 4);
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(Files.readAllBytes(p)));
                cache.put(MOD_ID + ":" + noExt, img);
                cache.put(noExt, img);
            }
        }
        return cache;
    }

    private static String resolveTabTranslationKey() {
        Optional<CreativeModeTab> tabOpt = BuiltInRegistries.CREATIVE_MODE_TAB.getOptional(
                ResourceLocation.fromNamespaceAndPath(MOD_ID, TAB_ID));
        assertTrue(tabOpt.isPresent(), "CreativeModeTab " + MOD_ID + ":" + TAB_ID + " must be registered");
        CreativeModeTab tab = tabOpt.get();
        if (tab.getDisplayName().getContents() instanceof TranslatableContents tc) {
            return tc.getKey();
        }
        fail("CreativeModeTab display name should use TranslatableContents, found: " + tab.getDisplayName());
        return "";
    }

    // =========================================================================
    // F1 — Project Charter & Xianxia Positioning Docs (Tier 1 & Tier 2)
    // =========================================================================

    @Test
    @DisplayName("F1-T1-01: Charter files exist with substantive strict UTF-8 content")
    void f1_t1_01_charterFilesExistAndValidUtf8() throws IOException {
        Path root = resolveProjectRoot();
        String readme = readUtf8File(root.resolve("README.md"), "README.md");
        String guide = readUtf8File(root.resolve("docs/DEVELOPMENT_GUIDE.md"), "docs/DEVELOPMENT_GUIDE.md");
        String license = readUtf8File(root.resolve("LICENSE"), "LICENSE");

        assertTrue(readme.getBytes(StandardCharsets.UTF_8).length >= 1500, "README.md should be >= 1500 bytes");
        assertTrue(guide.getBytes(StandardCharsets.UTF_8).length >= 1500, "DEVELOPMENT_GUIDE.md should be >= 1500 bytes");
        assertTrue(license.getBytes(StandardCharsets.UTF_8).length >= 200, "LICENSE should be >= 200 bytes");
    }

    @Test
    @DisplayName("F1-T1-02: README states MC 1.21.1, NeoForge, Xianxia positioning, and prototype scope")
    void f1_t1_02_readmeStatesPlatformXianxiaAndPrototypeScope() throws IOException {
        Path root = resolveProjectRoot();
        String readme = readUtf8File(root.resolve("README.md"), "README.md");
        String lower = readme.toLowerCase(Locale.ROOT);

        assertTrue(readme.contains("1.21.1"), "README.md must mention Minecraft 1.21.1");
        assertTrue(lower.contains("neoforge"), "README.md must mention NeoForge");
        assertTrue(readme.contains("\u4FEE\u4ED9") || lower.contains("xianxia"),
                "README.md must describe Chinese Xianxia cultivation positioning");
        assertTrue(readme.contains("\u5FA1\u5251") || readme.contains("\u98DE\u5251") || lower.contains("flying sword"),
                "README.md must describe flying sword / sword-riding positioning");
        assertTrue(readme.contains("3D") || readme.contains("\u5EFA\u6A21") || lower.contains("model"),
                "README.md must mention 3D model scope");
        assertTrue(readme.contains("\u811A\u90E8") || lower.contains("feet"),
                "README.md must mention Feet equipment slot scope");
        assertTrue(readme.contains("\u98DE\u884C") || lower.contains("flight"),
                "README.md must mention flight effect scope");
    }

    @Test
    @DisplayName("F1-T1-03: Docs cover all 5 placeholder domains and replacement locations")
    void f1_t1_03_docsCoverPlaceholderMatrixAndTargets() throws IOException {
        Path root = resolveProjectRoot();
        String combined = readUtf8File(root.resolve("README.md"), "README.md")
                + "\n"
                + readUtf8File(root.resolve("docs/DEVELOPMENT_GUIDE.md"), "docs/DEVELOPMENT_GUIDE.md");
        String lower = combined.toLowerCase(Locale.ROOT);

        assertTrue(combined.contains(MOD_ID) && combined.contains("mod_id"), "Must document mod_id / " + MOD_ID);
        assertTrue(combined.contains("mod_name"), "Must document mod_name placeholder");
        assertTrue(combined.contains(GROUP_ID) && combined.contains("mod_group_id"), "Must document mod_group_id / " + GROUP_ID);
        assertTrue(combined.contains("mod_authors"), "Must document mod_authors placeholder");
        assertTrue(combined.contains("mod_license"), "Must document mod_license placeholder");

        assertTrue(combined.contains("gradle.properties"), "Must mention gradle.properties replacement target");
        assertTrue(combined.contains("assets/") || combined.contains("assets\\"), "Must mention assets/ replacement target");
        assertTrue(combined.contains("data/") || combined.contains("data\\"), "Must mention data/ replacement target");
        assertTrue(combined.contains("LICENSE"), "Must mention LICENSE replacement target");
        assertTrue(lower.contains("package") || combined.contains("\u5305\u540D"), "Must mention Java package replacement target");
    }

    @Test
    @DisplayName("F1-T1-04: DEVELOPMENT_GUIDE documents directory layout matching actual files on disk")
    void f1_t1_04_developmentGuideMatchesDiskLayout() throws IOException {
        Path root = resolveProjectRoot();
        String guide = readUtf8File(root.resolve("docs/DEVELOPMENT_GUIDE.md"), "docs/DEVELOPMENT_GUIDE.md");

        for (String requiredDir : List.of("src/main/java", "src/main/resources", "src/main/templates", "src/test/java")) {
            assertTrue(guide.contains(requiredDir), "DEVELOPMENT_GUIDE.md must document " + requiredDir);
            assertTrue(Files.isDirectory(root.resolve(requiredDir)), "Documented directory must exist: " + requiredDir);
        }

        assertTrue(Files.isRegularFile(root.resolve("src/main/templates/META-INF/neoforge.mods.toml")));
        assertTrue(Files.isDirectory(root.resolve("src/main/resources/assets/" + MOD_ID)));
        assertTrue(Files.isDirectory(root.resolve("src/main/resources/data/" + MOD_ID)));

        for (String refPath : E2EComplianceValidators.extractCodePathsFromGuide(guide)) {
            if (refPath.startsWith("src/main/java/com/example/xianxia/")
                    || refPath.startsWith("src/main/resources/")
                    || refPath.startsWith("src/main/templates/")
                    || refPath.equals("README.md")
                    || refPath.equals("LICENSE")
                    || refPath.equals("build.gradle")
                    || refPath.equals("settings.gradle")
                    || refPath.equals("gradle.properties")) {
                assertTrue(Files.exists(root.resolve(refPath)),
                        "Path referenced in DEVELOPMENT_GUIDE.md does not exist on disk: " + refPath);
            }
        }
    }

    @Test
    @DisplayName("F1-T1-05: Docs specify Gradle wrapper commands and LICENSE matches gradle.properties")
    void f1_t1_05_docsSpecifyGradleCommandsAndLicenseMatches() throws IOException {
        Path root = resolveProjectRoot();
        String combined = readUtf8File(root.resolve("README.md"), "README.md")
                + "\n"
                + readUtf8File(root.resolve("docs/DEVELOPMENT_GUIDE.md"), "docs/DEVELOPMENT_GUIDE.md");
        String license = readUtf8File(root.resolve("LICENSE"), "LICENSE");
        Properties props = loadGradleProperties(root);

        assertTrue(combined.contains("gradlew"), "Docs must specify Gradle wrapper commands");
        assertTrue(combined.contains("build"), "Docs must document build command");
        assertTrue(combined.contains("test"), "Docs must document test command");
        assertTrue(combined.contains("runGameTestServer"), "Docs must document runGameTestServer command");

        String modLicense = props.getProperty("mod_license", "").trim();
        assertFalse(modLicense.isBlank(), "mod_license must be non-blank");
        assertTrue(license.toUpperCase(Locale.ROOT).contains(modLicense.toUpperCase(Locale.ROOT)),
                "LICENSE must match mod_license (" + modLicense + ")");
    }

    @Test
    @DisplayName("F1-T2-01: Docs and LICENSE contain zero TODO/TBD/FIXME/{{/}} or unexpanded tokens")
    void f1_t2_01_docsContainZeroStubMarkers() throws IOException {
        Path root = resolveProjectRoot();
        List<Path> targets = List.of(
                root.resolve("README.md"),
                root.resolve("docs/DEVELOPMENT_GUIDE.md"),
                root.resolve("LICENSE")
        );
        for (Path p : targets) {
            String text = readUtf8File(p, p.getFileName().toString());
            assertFalse(Pattern.compile("\\b(TODO|TBD|FIXME)\\b").matcher(text).find(),
                    "Found stub marker (TODO/TBD/FIXME) in " + p);
            assertFalse(text.contains("{{") || text.contains("}}"),
                    "Found mustache marker in " + p);
        }
        String license = readUtf8File(root.resolve("LICENSE"), "LICENSE");
        assertFalse(UNRESOLVED_TOKEN_PATTERN.matcher(license).find(),
                "LICENSE must not contain unexpanded ${...} tokens");
    }

    @Test
    @DisplayName("F1-T2-02: Relative Markdown links in README and DEVELOPMENT_GUIDE resolve on disk")
    void f1_t2_02_relativeMarkdownLinksResolve() throws IOException {
        Path root = resolveProjectRoot();
        String readme = readUtf8File(root.resolve("README.md"), "README.md");
        String guide = readUtf8File(root.resolve("docs/DEVELOPMENT_GUIDE.md"), "docs/DEVELOPMENT_GUIDE.md");

        E2EComplianceValidators.validateRelativeLinks(readme, root, "README.md");
        E2EComplianceValidators.validateRelativeLinks(guide, root.resolve("docs"), "docs/DEVELOPMENT_GUIDE.md");
    }

    @Test
    @DisplayName("F1-T2-03: Docs prescribe neoforge.mods.toml and never prescribe legacy mods.toml or net.minecraftforge")
    void f1_t2_03_noLegacyForgeMisguidanceInDocs() throws IOException {
        Path root = resolveProjectRoot();
        String combined = readUtf8File(root.resolve("README.md"), "README.md")
                + "\n"
                + readUtf8File(root.resolve("docs/DEVELOPMENT_GUIDE.md"), "docs/DEVELOPMENT_GUIDE.md");

        assertTrue(combined.contains("META-INF/neoforge.mods.toml"),
                "Docs must document active NeoForge 1.21.1 metadata path META-INF/neoforge.mods.toml");
        Pattern antiLegacyHint = Pattern.compile(
                "(?i)(legacy|old|do\\s+not|don't|avoid|never|not\\s+use|instead\\s+of|replaced|deprecated|\\u65E7\\u7248|\\u4E0D\\u8981|\\u5207\\u52FF|\\u7981\\u6B62|\\u800C\\u975E|\\u53D6\\u4EE3|\\u4E0D\\u518D)");
        for (String line : combined.split("\\R")) {
            if (line.contains("META-INF/mods.toml")) {
                assertTrue(antiLegacyHint.matcher(line).find(),
                        "Docs must not prescribe legacy META-INF/mods.toml outside explicit anti-legacy warnings: " + line);
            }
        }
        assertFalse(combined.contains("net.minecraftforge"),
                "Docs must not reference legacy net.minecraftforge loader");
    }

    @Test
    @DisplayName("F1-T2-04: Docs specify Java 21 toolchain")
    void f1_t2_04_docsSpecifyJava21Toolchain() throws IOException {
        Path root = resolveProjectRoot();
        String combined = readUtf8File(root.resolve("README.md"), "README.md")
                + "\n"
                + readUtf8File(root.resolve("docs/DEVELOPMENT_GUIDE.md"), "docs/DEVELOPMENT_GUIDE.md");
        assertTrue(Pattern.compile("(?i)java\\s*21|jdk\\s*21|toolchain.*21").matcher(combined).find(),
                "Docs must document Java 21 toolchain requirement");
    }

    @Test
    @DisplayName("F1-T2-05: Negative self-check verifies F1 validator rejects empty/stub/broken-link docs")
    void f1_t2_05_negativeSelfCheckRejectsBadDocs() {
        Path root = resolveProjectRoot();
        assertThrows(IllegalArgumentException.class, () ->
                E2EComplianceValidators.validateDocsContent("", "guide".repeat(400), "MIT".repeat(100), root, root, root));
        assertThrows(IllegalArgumentException.class, () ->
                E2EComplianceValidators.validateDocsContent("tiny stub", "guide".repeat(400), "MIT".repeat(100), root, root, root));
        String validReadme = ("Minecraft 1.21.1 NeoForge 修仙 御剑 3D 脚部 飞行 ".repeat(40));
        String brokenGuide = ("NeoForge 1.21.1 guide [broken](does/not/exist_anywhere.md) ".repeat(40));
        assertThrows(IllegalArgumentException.class, () ->
                E2EComplianceValidators.validateDocsContent(validReadme, brokenGuide, "MIT License ".repeat(30), root, root, root.resolve("docs")));
    }

    // =========================================================================
    // F2 — Placeholder Configuration & Metadata (Tier 1 & Tier 2)
    // =========================================================================

    @Test
    @DisplayName("F2-T1-01: gradle.properties defines valid NeoForge 1.21.1 keys and placeholder values")
    void f2_t1_01_gradlePropertiesValid() throws IOException {
        Path root = resolveProjectRoot();
        Properties props = loadGradleProperties(root);

        assertEquals("1.21.1", props.getProperty("minecraft_version"));
        assertTrue(props.getProperty("minecraft_version_range", "").contains("1.21.1"),
                "minecraft_version_range must cover 1.21.1");
        assertTrue(props.getProperty("neo_version", "").matches("^21\\.1\\.\\d+$"),
                "neo_version must match 21.1.x");
        assertEquals(MOD_ID, props.getProperty("mod_id"));
        assertTrue(E2EComplianceValidators.isValidModId(props.getProperty("mod_id")));
        assertEquals(GROUP_ID, props.getProperty("mod_group_id"));
        assertTrue(GROUP_ID.matches("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$"));

        for (String key : List.of("mod_name", "mod_version", "mod_authors", "mod_license")) {
            String val = props.getProperty(key, "");
            assertFalse(val.isBlank(), key + " must be non-blank in gradle.properties");
        }
    }

    @Test
    @DisplayName("F2-T1-02: Compiled @Mod entrypoint package and value match gradle.properties")
    void f2_t1_02_entrypointPackageAndRegistryMatchProperties() throws IOException {
        Path root = resolveProjectRoot();
        Properties props = loadGradleProperties(root);
        List<E2EClassfileInspector.ClassInfo> classes = loadMainClasses(root);
        E2EClassfileInspector.ClassInfo entrypoint = findModEntrypoint(classes);

        assertEquals(props.getProperty("mod_group_id"), entrypoint.packageName(),
                "@Mod entrypoint package must match mod_group_id");
        ResourceLocation expectedItemId = ResourceLocation.fromNamespaceAndPath(
                props.getProperty("mod_id"), ITEM_ID);
        assertTrue(BuiltInRegistries.ITEM.containsKey(expectedItemId),
                "Item registry must contain " + expectedItemId);
    }

    @Test
    @DisplayName("F2-T1-03: Expanded META-INF/neoforge.mods.toml schema and values match gradle.properties")
    void f2_t1_03_expandedModsTomlMatchesProperties() throws IOException {
        Path root = resolveProjectRoot();
        Properties props = loadGradleProperties(root);
        String toml = readExpandedModsToml(root);

        assertFalse(UNRESOLVED_TOKEN_PATTERN.matcher(toml).find(),
                "Generated neoforge.mods.toml must have zero unresolved ${...} tokens");
        assertTrue(toml.contains("modLoader=\"javafml\"") || toml.contains("modLoader = \"javafml\""),
                "neoforge.mods.toml must declare modLoader=\"javafml\"");
        assertTrue(toml.contains("[[mods]]"), "neoforge.mods.toml must declare [[mods]]");
        assertTrue(toml.contains(props.getProperty("mod_id")), "Must contain mod_id");
        assertTrue(toml.contains(props.getProperty("mod_version")), "Must contain mod_version");
        assertTrue(toml.contains(props.getProperty("mod_name")), "Must contain mod_name");
        assertTrue(toml.contains(props.getProperty("mod_authors")), "Must contain mod_authors");
        assertTrue(toml.contains(props.getProperty("mod_license")), "Must contain mod_license");

        assertTrue(toml.contains("[[dependencies." + MOD_ID + "]]"),
                "Must declare [[dependencies." + MOD_ID + "]]");
        assertTrue(toml.contains("modId=\"neoforge\"") || toml.contains("modId = \"neoforge\""),
                "Must depend on neoforge");
        assertTrue(toml.contains("modId=\"minecraft\"") || toml.contains("modId = \"minecraft\""),
                "Must depend on minecraft");
        assertTrue(toml.contains("type=\"required\"") || toml.contains("type = \"required\""),
                "Dependencies must use NeoForge 1.21.1 type=\"required\"");
    }

    @Test
    @DisplayName("F2-T1-04: Source template neoforge.mods.toml parameterizes all metadata placeholders")
    void f2_t1_04_sourceTemplateParameterizesPlaceholders() throws IOException {
        Path root = resolveProjectRoot();
        String template = readUtf8File(root.resolve("src/main/templates/META-INF/neoforge.mods.toml"),
                "src/main/templates/META-INF/neoforge.mods.toml");
        for (String token : List.of(
                "${mod_id}", "${mod_name}", "${mod_version}",
                "${mod_license}", "${mod_authors}", "${neo_version}", "${minecraft_version_range}")) {
            assertTrue(template.contains(token), "Source template must parameterize " + token);
        }
    }

    @Test
    @DisplayName("F2-T2-01: Negative/boundary check on NeoForge mod_id regex")
    void f2_t2_01_modIdRegexBoundaries() {
        assertTrue(E2EComplianceValidators.isValidModId("ab"));
        assertTrue(E2EComplianceValidators.isValidModId("a".repeat(64)));
        assertTrue(E2EComplianceValidators.isValidModId(MOD_ID));

        assertFalse(E2EComplianceValidators.isValidModId("XianxiaMod"));
        assertFalse(E2EComplianceValidators.isValidModId("xianxia-mod"));
        assertFalse(E2EComplianceValidators.isValidModId("1mod"));
        assertFalse(E2EComplianceValidators.isValidModId("a"));
        assertFalse(E2EComplianceValidators.isValidModId("a".repeat(65)));
        assertFalse(E2EComplianceValidators.isValidModId(""));
        assertFalse(E2EComplianceValidators.isValidModId("<mod_id>"));
    }

    @Test
    @DisplayName("F2-T2-02: Legacy META-INF/mods.toml is absent from sources and classpath")
    void f2_t2_02_legacyModsTomlAbsent() {
        Path root = resolveProjectRoot();
        assertFalse(Files.exists(root.resolve("src/main/resources/META-INF/mods.toml")));
        assertFalse(Files.exists(root.resolve("src/main/templates/META-INF/mods.toml")));
        assertNull(E2EComplianceTests.class.getClassLoader().getResource("META-INF/mods.toml"));
    }

    @Test
    @DisplayName("F2-T2-03: Zero unresolved ${...} tokens in processed metadata and resource outputs")
    void f2_t2_03_zeroUnresolvedTokensInProcessedOutputs() throws IOException {
        Path root = resolveProjectRoot();
        for (Path dir : List.of(
                root.resolve("build/generated/sources/modMetadata"),
                root.resolve("build/resources/main"))) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(dir)) {
                for (Path p : walk.filter(Files::isRegularFile).toList()) {
                    String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (name.endsWith(".toml") || name.endsWith(".json") || name.endsWith(".properties")) {
                        String content = Files.readString(p, StandardCharsets.UTF_8);
                        Matcher m = UNRESOLVED_TOKEN_PATTERN.matcher(content);
                        assertFalse(m.find(), "Unresolved token in " + p);
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("F2-T2-04: Resource namespace exclusivity (only xianxia_placeholder, zero stray examplemod)")
    void f2_t2_04_resourceNamespaceExclusivity() throws IOException {
        Path root = resolveProjectRoot();
        Path assetsDir = root.resolve("src/main/resources/assets");
        Path dataDir = root.resolve("src/main/resources/data");
        assertTrue(Files.isDirectory(assetsDir.resolve(MOD_ID)));
        assertTrue(Files.isDirectory(dataDir.resolve(MOD_ID)));

        assertFalse(Files.exists(assetsDir.resolve("examplemod")), "Stray examplemod folder under assets/");
        assertFalse(Files.exists(dataDir.resolve("examplemod")), "Stray examplemod folder under data/");

        try (Stream<Path> s = Files.list(assetsDir)) {
            List<String> dirs = s.filter(Files::isDirectory).map(p -> p.getFileName().toString()).toList();
            assertEquals(List.of(MOD_ID), dirs, "assets/ must contain only " + MOD_ID);
        }
    }

    @Test
    @DisplayName("F2-T2-05: Package segments contain no Java keywords and invalid ResourceLocation throws")
    void f2_t2_05_javaPackageAndResourceLocationValidation() throws IOException {
        Path root = resolveProjectRoot();
        Properties props = loadGradleProperties(root);
        String groupId = props.getProperty("mod_group_id", "");
        assertTrue(SourceVersion.isName(groupId), "mod_group_id must be a valid Java package name: " + groupId);
        assertThrows(ResourceLocationException.class,
                () -> ResourceLocation.fromNamespaceAndPath(MOD_ID, "Invalid Path!"));
    }

    // =========================================================================
    // F3 — NeoForge 1.21.1 Build & Skeleton (Tier 1 & Tier 2)
    // =========================================================================

    @Test
    @DisplayName("F3-T1-01: Gradle 9.x wrapper and Foojay/NeoForge settings.gradle exist")
    void f3_t1_01_gradleWrapperAndSettingsValid() throws IOException {
        Path root = resolveProjectRoot();
        assertTrue(Files.isRegularFile(root.resolve("gradlew")));
        assertTrue(Files.isRegularFile(root.resolve("gradlew.bat")));
        Path wrapperProps = root.resolve("gradle/wrapper/gradle-wrapper.properties");
        Path wrapperJar = root.resolve("gradle/wrapper/gradle-wrapper.jar");
        assertTrue(Files.isRegularFile(wrapperProps));
        assertTrue(Files.isRegularFile(wrapperJar));
        assertTrue(Files.size(wrapperJar) > 10_000, "gradle-wrapper.jar must be > 10KB");

        String propsText = readUtf8File(wrapperProps, "gradle-wrapper.properties");
        assertTrue(Pattern.compile("gradle-9\\.[0-9]+").matcher(propsText).find(),
                "gradle-wrapper.properties must target Gradle 9.x");

        String settings = readUtf8File(root.resolve("settings.gradle"), "settings.gradle");
        assertTrue(settings.contains("org.gradle.toolchains.foojay-resolver-convention"),
                "settings.gradle must configure foojay-resolver-convention");
        assertTrue(settings.contains("maven.neoforged.net") || settings.contains("gradlePluginPortal()"),
                "settings.gradle must configure gradlePluginPortal() or NeoForged Maven");
    }

    @Test
    @DisplayName("F3-T1-02: build.gradle configures ModDevGradle, Java 21, GameTest, unitTest, and -Pe2e hook")
    void f3_t1_02_buildGradleConfiguresModDevAndE2eHook() throws IOException {
        Path root = resolveProjectRoot();
        String buildGradle = readUtf8File(root.resolve("build.gradle"), "build.gradle");
        assertTrue(buildGradle.contains("net.neoforged.moddev"), "Must apply net.neoforged.moddev");
        assertTrue(buildGradle.contains("JavaLanguageVersion.of(21)"), "Must target Java 21 toolchain");
        assertTrue(buildGradle.contains("gameTestServer"), "Must define gameTestServer run");
        assertTrue(buildGradle.contains("neoforge.enabledGameTestNamespaces"), "Must configure enabledGameTestNamespaces");
        assertTrue(buildGradle.contains("unitTest"), "Must enable neoForge.unitTest");
        assertTrue(buildGradle.contains("useJUnitPlatform()"), "Must enable JUnit 5 platform");
        assertTrue(buildGradle.contains("hasProperty('e2e')") || buildGradle.contains("hasProperty(\"e2e\")"),
                "Must preserve -Pe2e sourceSet hook");
        assertTrue(buildGradle.contains("e2e/main/java") && buildGradle.contains("e2e/test/java"));
    }

    @Test
    @DisplayName("F3-T1-03: Dynamically discovered @Mod entrypoint exposes public (IEventBus, ModContainer) constructor")
    void f3_t1_03_modEntrypointConstructorContract() throws Exception {
        Path root = resolveProjectRoot();
        List<E2EClassfileInspector.ClassInfo> classes = loadMainClasses(root);
        E2EClassfileInspector.ClassInfo entrypoint = findModEntrypoint(classes);

        boolean hasPublicCtor = entrypoint.methods().stream().anyMatch(m ->
                "<init>".equals(m.name())
                        && m.isPublic()
                        && "(Lnet/neoforged/bus/api/IEventBus;Lnet/neoforged/fml/ModContainer;)V".equals(m.descriptor()));
        assertTrue(hasPublicCtor, entrypoint.binaryName() + " must expose public constructor (IEventBus, ModContainer)");

        Class<?> loaded = Class.forName(entrypoint.binaryName(), false, E2EComplianceTests.class.getClassLoader());
        assertNotNull(loaded.getConstructor(IEventBus.class, ModContainer.class));
    }

    @Test
    @DisplayName("F3-T1-04: Production JAR in build/libs contains metadata, classes, 3D model, PNG, langs, and NBT")
    void f3_t1_04_productionJarCompleteness() throws IOException {
        Path root = resolveProjectRoot();
        Path jarPath = findProductionJar(root);
        List<E2EClassfileInspector.ClassInfo> jarClasses = loadJarClasses(jarPath);
        E2EClassfileInspector.ClassInfo entrypoint = findModEntrypoint(jarClasses);
        assertNotNull(entrypoint);

        try (JarFile jar = new JarFile(jarPath.toFile())) {
            for (String requiredEntry : List.of(
                    "META-INF/MANIFEST.MF",
                    "META-INF/neoforge.mods.toml",
                    "assets/" + MOD_ID + "/models/item/" + ITEM_ID + ".json",
                    "assets/" + MOD_ID + "/textures/item/" + ITEM_ID + ".png",
                    "assets/" + MOD_ID + "/lang/en_us.json",
                    "assets/" + MOD_ID + "/lang/zh_cn.json",
                    "data/" + MOD_ID + "/structure/empty3x3x3.nbt"
            )) {
                assertNotNull(jar.getJarEntry(requiredEntry), "Production JAR missing entry: " + requiredEntry);
            }

            JarEntry tomlEntry = jar.getJarEntry("META-INF/neoforge.mods.toml");
            try (InputStream in = jar.getInputStream(tomlEntry)) {
                String toml = E2EComplianceValidators.decodeStrictUtf8(in.readAllBytes(), "JAR neoforge.mods.toml");
                assertFalse(UNRESOLVED_TOKEN_PATTERN.matcher(toml).find(),
                        "JAR META-INF/neoforge.mods.toml must have zero ${...} tokens");
            }

            boolean hasItemSubclass = jarClasses.stream()
                    .anyMatch(c -> "net/minecraft/world/item/Item".equals(c.superName())
                            && c.interfaces().contains("net/minecraft/world/item/Equipable"));
            assertTrue(hasItemSubclass, "Production JAR must include compiled Equipable Item subclass");
        }
    }

    @Test
    @DisplayName("F3-T2-01: Compiled bytecode major version is strictly Java 21 (65, < 69)")
    void f3_t2_01_bytecodeMajorVersionJava21() throws IOException {
        Path root = resolveProjectRoot();
        for (E2EClassfileInspector.ClassInfo ci : loadMainClasses(root)) {
            assertEquals(65, ci.majorVersion(),
                    "Expected Java 21 bytecode (major 65) for " + ci.binaryName() + ", got " + ci.majorVersion());
        }
        Path jarPath = findProductionJar(root);
        for (E2EClassfileInspector.ClassInfo ci : loadJarClasses(jarPath)) {
            assertTrue(ci.majorVersion() == 65 && ci.majorVersion() < 69,
                    "JAR class " + ci.binaryName() + " leaked wrong bytecode major version: " + ci.majorVersion());
        }
    }

    @Test
    @DisplayName("F3-T2-02: Common/server classes have zero client or LWJGL class references")
    void f3_t2_02_dedicatedServerClassloadingIsolation() throws IOException {
        Path root = resolveProjectRoot();
        List<E2EClassfileInspector.ClassInfo> classes = loadMainClasses(root);
        Set<String> clientOnlyNames = collectClientOnlyInternalNames(classes);
        for (E2EClassfileInspector.ClassInfo ci : classes) {
            if (clientOnlyNames.contains(ci.internalName())) {
                continue;
            }
            for (String ref : ci.classConstants()) {
                assertFalse(ref.startsWith("net/minecraft/client/"),
                        "Common/server class " + ci.binaryName() + " references client class: " + ref);
                assertFalse(ref.startsWith("net/neoforged/neoforge/client/"),
                        "Common/server class " + ci.binaryName() + " references NeoForge client class: " + ref);
                assertFalse(ref.startsWith("org/lwjgl/"),
                        "Common/server class " + ci.binaryName() + " references LWJGL class: " + ref);
            }
        }
    }

    @Test
    @DisplayName("F3-T2-03: Production JAR excludes test classes, E2E classes, .agents, and .git")
    void f3_t2_03_productionJarHygiene() throws IOException {
        Path root = resolveProjectRoot();
        Path jarPath = findProductionJar(root);
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                String lower = name.toLowerCase(Locale.ROOT);
                assertFalse(name.startsWith(E2E_INTERNAL_PREFIX), "Production JAR must exclude E2E classes: " + name);
                assertFalse(lower.contains("junit"), "Production JAR must exclude JUnit artifacts: " + name);
                assertFalse(lower.contains("unittests") || lower.contains("compliancetests"),
                        "Production JAR must exclude unit/compliance tests: " + name);
                assertFalse(name.contains(".agents/") || name.contains(".git/") || name.contains("src/test/"),
                        "Production JAR must exclude agent/git/test metadata: " + name);
            }
        }
    }

    @Test
    @DisplayName("F3-T2-04: GameTest structure empty3x3x3.nbt is valid GZIP NBT under singular structure/")
    void f3_t2_04_structureNbtValidAndSingularPath() throws IOException {
        Path root = resolveProjectRoot();
        Path nbtPath = root.resolve("src/main/resources/data/" + MOD_ID + "/structure/empty3x3x3.nbt");
        assertTrue(Files.isRegularFile(nbtPath), "Missing GameTest structure NBT: " + nbtPath);
        assertFalse(Files.exists(root.resolve("src/main/resources/data/" + MOD_ID + "/structures")),
                "1.21.1 must use singular structure/ folder, not plural structures/");

        byte[] raw = Files.readAllBytes(nbtPath);
        assertTrue(raw.length >= 4 && (raw[0] & 0xFF) == 0x1F && (raw[1] & 0xFF) == 0x8B,
                "empty3x3x3.nbt must be GZIP-compressed");
        CompoundTag tag = NbtIo.readCompressed(new ByteArrayInputStream(raw), NbtAccounter.unlimitedHeap());
        assertNotNull(tag);
        assertTrue(tag.contains("size"), "Structure NBT must define 'size'");
    }

    @Test
    @DisplayName("F3-T2-05: Zero legacy mandatory= lines and zero 1.21.2+ symbols in compiled classes")
    void f3_t2_05_zeroLegacyMandatoryOr1212Symbols() throws IOException {
        Path root = resolveProjectRoot();
        String toml = readExpandedModsToml(root);
        assertFalse(Pattern.compile("(?m)^\\s*mandatory\\s*=").matcher(toml).find(),
                "1.21.1 neoforge.mods.toml must not use legacy mandatory=");

        List<String> forbidden1212 = List.of("EQUIPPABLE", "EquipmentAsset", "PlayerRenderState", "getPlayerRenderer");
        for (E2EClassfileInspector.ClassInfo ci : loadMainClasses(root)) {
            for (String utf8 : ci.utf8Constants()) {
                for (String sym : forbidden1212) {
                    assertFalse(utf8.contains(sym),
                            "Class " + ci.binaryName() + " references 1.21.2+ symbol '" + sym + "' in constant: " + utf8);
                }
            }
        }
    }

    // =========================================================================
    // F7 — Multi-Part 3D Xianxia Jian Model, Texture & Lang (Tier 1 & Tier 2)
    // =========================================================================

    @Test
    @DisplayName("F7-T1-01: 3D item model JSON is custom multi-part (>=12 elements) with resolved PNG textures")
    void f7_t1_01_modelHasTwelvePlusElementsAndResolvedTextures() throws IOException {
        Path root = resolveProjectRoot();
        String modelJson = readUtf8File(
                root.resolve("src/main/resources/assets/" + MOD_ID + "/models/item/" + ITEM_ID + ".json"),
                ITEM_ID + ".json");
        Map<String, BufferedImage> textures = buildTextureMap(root);
        E2EComplianceValidators.validateModelJson(modelJson, textures::get, false, false, false);
    }

    @Test
    @DisplayName("F7-T1-02: Xianxia Jian geometry has >=12u length, >=3.5u crossguard, >=4 width/depth tiers, >=1.2u Z-depth")
    void f7_t1_02_modelXianxiaJianGeometry() throws IOException {
        Path root = resolveProjectRoot();
        String modelJson = readUtf8File(
                root.resolve("src/main/resources/assets/" + MOD_ID + "/models/item/" + ITEM_ID + ".json"),
                ITEM_ID + ".json");
        Map<String, BufferedImage> textures = buildTextureMap(root);
        E2EComplianceValidators.validateModelJson(modelJson, textures::get, true, false, false);
    }

    @Test
    @DisplayName("F7-T1-03: Model display defines all 7 standard item transform contexts")
    void f7_t1_03_modelDisplayAllSevenContexts() throws IOException {
        Path root = resolveProjectRoot();
        String modelJson = readUtf8File(
                root.resolve("src/main/resources/assets/" + MOD_ID + "/models/item/" + ITEM_ID + ".json"),
                ITEM_ID + ".json");
        Map<String, BufferedImage> textures = buildTextureMap(root);
        E2EComplianceValidators.validateModelJson(modelJson, textures::get, false, true, false);
    }

    @Test
    @DisplayName("F7-T1-04: Sword PNG texture is PoT RGBA >=16x16 with >=12 colors, transparency, and multi-hue palette")
    void f7_t1_04_texturePngResolutionAlphaAndXianxiaPalette() throws IOException {
        Path root = resolveProjectRoot();
        Path pngPath = root.resolve("src/main/resources/assets/" + MOD_ID + "/textures/item/" + ITEM_ID + ".png");
        assertTrue(Files.isRegularFile(pngPath), "Texture PNG must exist at " + pngPath);
        byte[] raw = Files.readAllBytes(pngPath);
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(raw));
        E2EComplianceValidators.validateTexturePng(raw, img);
    }

    @Test
    @DisplayName("F7-T1-05: Bilingual localization (en_us.json & zh_cn.json) defines non-blank keys and CJK text")
    void f7_t1_05_bilingualLocalizationCompleteness() throws IOException {
        Path root = resolveProjectRoot();
        String enRaw = readUtf8File(root.resolve("src/main/resources/assets/" + MOD_ID + "/lang/en_us.json"), "en_us.json");
        String zhRaw = readUtf8File(root.resolve("src/main/resources/assets/" + MOD_ID + "/lang/zh_cn.json"), "zh_cn.json");

        JsonObject en = JsonParser.parseString(enRaw).getAsJsonObject();
        JsonObject zh = JsonParser.parseString(zhRaw).getAsJsonObject();
        String itemKey = "item." + MOD_ID + "." + ITEM_ID;
        String tabKey = resolveTabTranslationKey();

        for (String key : List.of(itemKey, tabKey)) {
            assertTrue(en.has(key) && !en.get(key).getAsString().isBlank(), "en_us.json missing/blank key: " + key);
            assertTrue(zh.has(key) && !zh.get(key).getAsString().isBlank(), "zh_cn.json missing/blank key: " + key);
            assertTrue(CJK_PATTERN.matcher(zh.get(key).getAsString()).find(),
                    "zh_cn.json value for " + key + " must contain Chinese CJK characters");
        }
    }

    @Test
    @DisplayName("F7-T2-01: Model element bounds [-16,32], valid 22.5-step rotation angles, and UV [0,16]")
    void f7_t2_01_modelBoundsRotationsAndUvsValid() throws IOException {
        Path root = resolveProjectRoot();
        String modelJson = readUtf8File(
                root.resolve("src/main/resources/assets/" + MOD_ID + "/models/item/" + ITEM_ID + ".json"),
                ITEM_ID + ".json");
        Map<String, BufferedImage> textures = buildTextureMap(root);
        E2EComplianceValidators.validateModelJson(modelJson, textures::get, false, false, false);
    }

    @Test
    @DisplayName("F7-T2-02: Negative self-check rejects 2D item/generated, <12 elements, 15-deg rotation, dangling texture")
    void f7_t2_02_negativeModelValidatorSelfCheck() {
        BufferedImage dummy = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Function<String, BufferedImage> okTex = k -> dummy;

        assertThrows(IllegalArgumentException.class, () -> E2EComplianceValidators.validateModelJson(
                "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"x:y\"}}", okTex, false, false, false));

        String oneCube = "{\"textures\":{\"t\":\"x:y\"},\"elements\":[{\"from\":[0,0,0],\"to\":[1,1,1],\"faces\":{\"up\":{\"texture\":\"#t\"}}}]}";
        assertThrows(IllegalArgumentException.class, () ->
                E2EComplianceValidators.validateModelJson(oneCube, okTex, false, false, false));

        String elevenCubes = buildSyntheticModel(11, 0.0, "#t");
        assertThrows(IllegalArgumentException.class, () ->
                E2EComplianceValidators.validateModelJson(elevenCubes, okTex, false, false, false));

        String badAngle = buildSyntheticModel(12, 15.0, "#t");
        assertThrows(IllegalArgumentException.class, () ->
                E2EComplianceValidators.validateModelJson(badAngle, okTex, false, false, false));

        String danglingTex = buildSyntheticModel(12, 0.0, "#missing");
        assertThrows(IllegalArgumentException.class, () ->
                E2EComplianceValidators.validateModelJson(danglingTex, okTex, false, false, false));
    }

    @Test
    @DisplayName("F7-T2-03: Negative self-check rejects solid, non-PoT, undersized, and 2-color PNGs")
    void f7_t2_03_negativeTextureValidatorSelfCheck() throws IOException {
        byte[] solid64 = encodePng(makeSolidImage(64, 64, 0xFF336699));
        assertThrows(IllegalArgumentException.class, () ->
                E2EComplianceValidators.validateTexturePng(solid64, ImageIO.read(new ByteArrayInputStream(solid64))));

        byte[] nonPot15 = encodePng(makeSolidImage(15, 15, 0xFF336699));
        assertThrows(IllegalArgumentException.class, () ->
                E2EComplianceValidators.validateTexturePng(nonPot15, ImageIO.read(new ByteArrayInputStream(nonPot15))));

        byte[] small8 = encodePng(makeSolidImage(8, 8, 0xFF336699));
        assertThrows(IllegalArgumentException.class, () ->
                E2EComplianceValidators.validateTexturePng(small8, ImageIO.read(new ByteArrayInputStream(small8))));

        BufferedImage twoColor = makeSolidImage(16, 16, 0xFF205080);
        twoColor.setRGB(0, 0, 0xFFD4AF37);
        byte[] twoColorBytes = encodePng(twoColor);
        assertThrows(IllegalArgumentException.class, () ->
                E2EComplianceValidators.validateTexturePng(twoColorBytes, ImageIO.read(new ByteArrayInputStream(twoColorBytes))));

        BufferedImage narrowCluster = makeSolidImage(16, 16, 0x00000000);
        for (int i = 0; i < 10; i++) {
            narrowCluster.setRGB(i + 1, 0, 0xFF0000F0 + i);
        }
        narrowCluster.setRGB(12, 0, 0xFFF00000);
        narrowCluster.setRGB(13, 0, 0xFFF10000);
        byte[] narrowClusterBytes = encodePng(narrowCluster);
        assertThrows(IllegalArgumentException.class, () ->
                E2EComplianceValidators.validateTexturePng(narrowClusterBytes, ImageIO.read(new ByteArrayInputStream(narrowClusterBytes))));
    }

    @Test
    @DisplayName("F7-T2-04: Display transform scale in (0,4] and translation in [-80,80]")
    void f7_t2_04_displayTransformRangeGuard() throws IOException {
        Path root = resolveProjectRoot();
        String modelJson = readUtf8File(
                root.resolve("src/main/resources/assets/" + MOD_ID + "/models/item/" + ITEM_ID + ".json"),
                ITEM_ID + ".json");
        Map<String, BufferedImage> textures = buildTextureMap(root);
        E2EComplianceValidators.validateModelJson(modelJson, textures::get, false, true, false);
    }

    @Test
    @DisplayName("F7-T2-05: Every face UV rectangle maps to at least one non-transparent PNG pixel")
    void f7_t2_05_uvMapsToNonTransparentPixels() throws IOException {
        Path root = resolveProjectRoot();
        String modelJson = readUtf8File(
                root.resolve("src/main/resources/assets/" + MOD_ID + "/models/item/" + ITEM_ID + ".json"),
                ITEM_ID + ".json");
        Map<String, BufferedImage> textures = buildTextureMap(root);
        E2EComplianceValidators.validateModelJson(modelJson, textures::get, false, false, true);
    }

    // =========================================================================
    // F8 — Feet-Equipped Riding Sword 3D Render Setup & Packaging (Tier 1 & Tier 2)
    // =========================================================================

    @Test
    @DisplayName("F8-T1-01: Client @EventBusSubscriber(Dist.CLIENT) and RenderLayer classes exist in build & JAR")
    void f8_t1_01_clientClassesPresentInBuildAndJar() throws IOException {
        Path root = resolveProjectRoot();
        List<E2EClassfileInspector.ClassInfo> mainClasses = loadMainClasses(root);
        E2EClassfileInspector.ClassInfo subscriber = findClientSubscriber(mainClasses);
        E2EClassfileInspector.ClassInfo layer = findRenderLayerClass(mainClasses);

        Path jarPath = findProductionJar(root);
        List<E2EClassfileInspector.ClassInfo> jarClasses = loadJarClasses(jarPath);
        assertTrue(jarClasses.stream().anyMatch(c -> c.internalName().equals(subscriber.internalName())),
                "Production JAR missing client subscriber class: " + subscriber.binaryName());
        assertTrue(jarClasses.stream().anyMatch(c -> c.internalName().equals(layer.internalName())),
                "Production JAR missing RenderLayer class: " + layer.binaryName());
    }

    @Test
    @DisplayName("F8-T1-02: Client subscriber handles EntityRenderersEvent$AddLayers via getSkins/getSkin/addLayer")
    void f8_t1_02_clientSubscriberAddLayersContract() throws IOException {
        Path root = resolveProjectRoot();
        List<E2EClassfileInspector.ClassInfo> classes = loadMainClasses(root);
        E2EClassfileInspector.ClassInfo subscriber = findClientSubscriber(classes);
        E2EClassfileInspector.ClassInfo layer = findRenderLayerClass(classes);

        List<E2EClassfileInspector.MethodInfo> addLayerHandlers = subscriber.methods().stream()
                .filter(m -> m.isStatic()
                        && m.hasAnnotation("Lnet/neoforged/bus/api/SubscribeEvent;")
                        && m.descriptor().contains("Lnet/neoforged/neoforge/client/event/EntityRenderersEvent$AddLayers;"))
                .toList();
        assertFalse(addLayerHandlers.isEmpty(),
                subscriber.binaryName() + " must expose static @SubscribeEvent method accepting EntityRenderersEvent.AddLayers");

        List<E2EClassfileInspector.MemberRef> allRefs = new ArrayList<>();
        allRefs.addAll(subscriber.methodRefs());

        boolean callsGetSkins = allRefs.stream().anyMatch(r ->
                r.owner().equals("net/neoforged/neoforge/client/event/EntityRenderersEvent$AddLayers")
                        && r.name().equals("getSkins"));
        boolean callsGetSkin = allRefs.stream().anyMatch(r ->
                r.owner().equals("net/neoforged/neoforge/client/event/EntityRenderersEvent$AddLayers")
                        && r.name().equals("getSkin"));
        boolean callsAddLayer = allRefs.stream().anyMatch(r ->
                r.name().equals("addLayer")
                        && (r.owner().equals("net/minecraft/client/renderer/entity/player/PlayerRenderer")
                        || r.owner().equals("net/minecraft/client/renderer/entity/LivingEntityRenderer")));
        boolean constructsLayer = subscriber.classConstants().contains(layer.internalName());

        assertTrue(callsGetSkins, "AddLayers handler must invoke getSkins()");
        assertTrue(callsGetSkin, "AddLayers handler must invoke getSkin(...)");
        assertTrue(callsAddLayer, "AddLayers handler must invoke PlayerRenderer/LivingEntityRenderer.addLayer(...)");
        assertTrue(constructsLayer, "AddLayers handler must instantiate discovered RenderLayer subclass");
    }

    @Test
    @DisplayName("F8-T1-03: Discovered RenderLayer uses AbstractClientPlayer, FEET slot check, PoseStack, and renderStatic")
    void f8_t1_03_renderLayerBytecodeContract() throws IOException {
        Path root = resolveProjectRoot();
        E2EClassfileInspector.ClassInfo layer = findRenderLayerClass(loadMainClasses(root));
        assertEquals("net/minecraft/client/renderer/entity/layers/RenderLayer", layer.superName());

        E2EClassfileInspector.MethodInfo renderMethod = findRenderMethod(layer);
        assertTrue(renderMethod.descriptor().contains("Lnet/minecraft/client/player/AbstractClientPlayer;"),
                "1.21.1 RenderLayer.render must accept AbstractClientPlayer");

        List<E2EClassfileInspector.MemberRef> refs = new ArrayList<>();
        collectTransitiveRefs(layer, renderMethod, new HashSet<>(), refs);

        boolean checksFeet = refs.stream().anyMatch(r ->
                r.owner().equals("net/minecraft/world/entity/EquipmentSlot") && r.name().equals("FEET"));
        boolean readsSlot = refs.stream().anyMatch(r -> r.name().equals("getItemBySlot"));
        boolean transformsPose = refs.stream().anyMatch(r -> r.owner().equals("com/mojang/blaze3d/vertex/PoseStack"));
        boolean callsRenderStatic = refs.stream().anyMatch(r ->
                r.owner().equals("net/minecraft/client/renderer/entity/ItemRenderer") && r.name().equals("renderStatic"));

        assertTrue(checksFeet, "RenderLayer.render must query EquipmentSlot.FEET");
        assertTrue(readsSlot, "RenderLayer.render must call getItemBySlot");
        assertTrue(transformsPose, "RenderLayer.render must transform PoseStack");
        assertTrue(callsRenderStatic, "RenderLayer.render must reuse 3D item model via ItemRenderer.renderStatic");
    }

    @Test
    @DisplayName("F8-T1-04: Registered sword is NOT ArmorItem and resolves cleanly to 3D model and PNG texture")
    void f8_t1_04_nonArmorItemAndAssetResolution() {
        Path root = resolveProjectRoot();
        Item sword = BuiltInRegistries.ITEM.getOptional(ResourceLocation.fromNamespaceAndPath(MOD_ID, ITEM_ID))
                .orElseThrow(() -> new AssertionError("Sword item not registered"));
        assertFalse(sword instanceof ArmorItem,
                "Flying sword must NOT extend ArmorItem (avoids missing vanilla armor layer textures)");
        assertTrue(Files.isRegularFile(root.resolve("src/main/resources/assets/" + MOD_ID + "/models/item/" + ITEM_ID + ".json")));
        assertTrue(Files.isRegularFile(root.resolve("src/main/resources/assets/" + MOD_ID + "/textures/item/" + ITEM_ID + ".png")));
    }

    @Test
    @DisplayName("F8-T2-01: Zero leakage of client classes into @Mod or other common/server classes")
    void f8_t2_01_zeroClientLeakageIntoCommonClasses() throws IOException {
        Path root = resolveProjectRoot();
        List<E2EClassfileInspector.ClassInfo> classes = loadMainClasses(root);
        Set<String> clientInternals = collectClientOnlyInternalNames(classes);

        for (E2EClassfileInspector.ClassInfo ci : classes) {
            if (clientInternals.contains(ci.internalName())) {
                continue;
            }
            for (String ref : ci.classConstants()) {
                assertFalse(clientInternals.contains(ref),
                        "Common/server class " + ci.binaryName() + " must not reference client class " + ref);
                assertFalse(ref.startsWith("net/minecraft/client/"),
                        "Common/server class " + ci.binaryName() + " must not reference " + ref);
            }
        }
    }

    @Test
    @DisplayName("F8-T2-02: AddLayers handler covers both wide and slim skins via getSkins()")
    void f8_t2_02_addLayersCoversAllSkinsViaGetSkins() throws IOException {
        Path root = resolveProjectRoot();
        E2EClassfileInspector.ClassInfo subscriber = findClientSubscriber(loadMainClasses(root));
        boolean invokesGetSkins = subscriber.methods().stream()
                .filter(m -> m.descriptor().contains("EntityRenderersEvent$AddLayers"))
                .flatMap(m -> m.instructions().stream())
                .map(E2EClassfileInspector.Instruction::memberRef)
                .anyMatch(r -> r != null
                        && r.owner().equals("net/neoforged/neoforge/client/event/EntityRenderersEvent$AddLayers")
                        && r.name().equals("getSkins"));
        assertTrue(invokesGetSkins, "AddLayers handler must iterate event.getSkins() for wide and slim skins");
    }

    @Test
    @DisplayName("F8-T2-03: RenderLayer.render branches conditionally on FEET sword check before renderStatic")
    void f8_t2_03_renderLayerConditionalBranchBeforeRenderStatic() throws IOException {
        Path root = resolveProjectRoot();
        E2EClassfileInspector.ClassInfo layer = findRenderLayerClass(loadMainClasses(root));
        E2EClassfileInspector.MethodInfo renderMethod = findRenderMethod(layer);
        assertTrue(hasConditionalFeetCheckBeforeRenderStatic(layer, renderMethod),
                "RenderLayer.render must conditionally branch on EquipmentSlot.FEET/getItemBySlot before ItemRenderer.renderStatic(...)");
    }

    @Test
    @DisplayName("F8-T2-04: RenderLayer PoseStack pushPose/popPose symmetry, ordering, and zero raw LWJGL/GL11 calls")
    void f8_t2_04_poseStackSymmetryAndZeroRawGl() throws IOException {
        Path root = resolveProjectRoot();
        E2EClassfileInspector.ClassInfo layer = findRenderLayerClass(loadMainClasses(root));
        E2EClassfileInspector.MethodInfo renderMethod = findRenderMethod(layer);
        assertPoseStackSymmetryAndZeroRawGl(layer, renderMethod);
    }

    @Test
    @DisplayName("F8-T2-05: Client classes contain zero 1.21.2+ client rendering APIs")
    void f8_t2_05_zero1212ClientApiBleed() throws IOException {
        Path root = resolveProjectRoot();
        List<String> forbidden = List.of("PlayerRenderState", "getPlayerRenderer", "EquipmentAsset");
        List<E2EClassfileInspector.ClassInfo> classes = loadMainClasses(root);
        Set<String> clientOnlyNames = collectClientOnlyInternalNames(classes);
        for (E2EClassfileInspector.ClassInfo ci : classes) {
            if (!clientOnlyNames.contains(ci.internalName())) {
                continue;
            }
            for (String utf8 : ci.utf8Constants()) {
                for (String sym : forbidden) {
                    assertFalse(utf8.contains(sym),
                            "Client class " + ci.binaryName() + " references 1.21.2+ symbol: " + sym);
                }
            }
        }
    }

    // =========================================================================
    // Tier 3 — Cross-Feature Pairwise Compliance (T3-04, T3-05, T3-06, T3-07, T3-09, T3-10)
    // =========================================================================

    @Test
    @DisplayName("T3-04 (F4xF7): Registered item ResourceLocation matches 3D JSON model and PNG texture assets")
    void t3_04_registryIdMatchesModelAndTextureAssets() throws IOException {
        Path root = resolveProjectRoot();
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(MOD_ID, ITEM_ID);
        assertTrue(BuiltInRegistries.ITEM.containsKey(id));

        Path modelPath = root.resolve("src/main/resources/assets/" + id.getNamespace() + "/models/item/" + id.getPath() + ".json");
        String modelJson = readUtf8File(modelPath, modelPath.toString());
        Map<String, BufferedImage> textures = buildTextureMap(root);
        E2EComplianceValidators.validateModelJson(modelJson, textures::get, true, true, true);
    }

    @Test
    @DisplayName("T3-05 (F4xF7): Registered item and tab translation keys match en_us.json and zh_cn.json")
    void t3_05_registeredKeysMatchLangFiles() throws IOException {
        Path root = resolveProjectRoot();
        Item sword = BuiltInRegistries.ITEM.getOptional(ResourceLocation.fromNamespaceAndPath(MOD_ID, ITEM_ID))
                .orElseThrow();
        String itemKey = sword.getDescriptionId();
        String tabKey = resolveTabTranslationKey();

        JsonObject en = JsonParser.parseString(
                readUtf8File(root.resolve("src/main/resources/assets/" + MOD_ID + "/lang/en_us.json"), "en_us.json"))
                .getAsJsonObject();
        JsonObject zh = JsonParser.parseString(
                readUtf8File(root.resolve("src/main/resources/assets/" + MOD_ID + "/lang/zh_cn.json"), "zh_cn.json"))
                .getAsJsonObject();

        for (String key : List.of(itemKey, tabKey)) {
            assertTrue(en.has(key) && !en.get(key).getAsString().isBlank(), "Missing en_us entry for " + key);
            assertTrue(zh.has(key) && !zh.get(key).getAsString().isBlank(), "Missing zh_cn entry for " + key);
        }
    }

    @Test
    @DisplayName("T3-06 (F2xF3xF4): gradle.properties, generated neoforge.mods.toml, assets/data, and registries share namespace parity")
    void t3_06_crossLayerNamespaceAndMetadataParity() throws IOException {
        Path root = resolveProjectRoot();
        Properties props = loadGradleProperties(root);
        String modId = props.getProperty("mod_id");
        String toml = readExpandedModsToml(root);

        assertTrue(toml.contains("modId=\"" + modId + "\"") || toml.contains("modId = \"" + modId + "\""));
        assertTrue(Files.isDirectory(root.resolve("src/main/resources/assets/" + modId)));
        assertTrue(Files.isDirectory(root.resolve("src/main/resources/data/" + modId)));
        assertTrue(BuiltInRegistries.ITEM.containsKey(ResourceLocation.fromNamespaceAndPath(modId, ITEM_ID)));
        assertTrue(BuiltInRegistries.CREATIVE_MODE_TAB.containsKey(ResourceLocation.fromNamespaceAndPath(modId, TAB_ID)));
    }

    @Test
    @DisplayName("T3-07 (F1xF2): README, DEVELOPMENT_GUIDE, and LICENSE placeholder matrix matches gradle.properties")
    void t3_07_docsPlaceholderMatrixMatchesProperties() throws IOException {
        Path root = resolveProjectRoot();
        Properties props = loadGradleProperties(root);
        String combined = readUtf8File(root.resolve("README.md"), "README.md")
                + "\n"
                + readUtf8File(root.resolve("docs/DEVELOPMENT_GUIDE.md"), "docs/DEVELOPMENT_GUIDE.md");
        String license = readUtf8File(root.resolve("LICENSE"), "LICENSE");

        assertTrue(combined.contains(props.getProperty("mod_id")));
        assertTrue(combined.contains(props.getProperty("mod_group_id")));
        assertTrue(license.toUpperCase(Locale.ROOT).contains(props.getProperty("mod_license").toUpperCase(Locale.ROOT)));
        E2EComplianceValidators.validateDocsContent(
                readUtf8File(root.resolve("README.md"), "README.md"),
                readUtf8File(root.resolve("docs/DEVELOPMENT_GUIDE.md"), "docs/DEVELOPMENT_GUIDE.md"),
                license,
                root,
                root,
                root.resolve("docs"));
    }

    @Test
    @DisplayName("T3-09 (F5xF8 static): Non-ArmorItem sword pairs with 3D model and compiled client RenderLayer")
    void t3_09_nonArmorSwordPairedWithClientRenderLayer() throws IOException {
        Path root = resolveProjectRoot();
        Item sword = BuiltInRegistries.ITEM.getOptional(ResourceLocation.fromNamespaceAndPath(MOD_ID, ITEM_ID))
                .orElseThrow();
        assertFalse(sword instanceof ArmorItem);
        List<E2EClassfileInspector.ClassInfo> classes = loadMainClasses(root);
        assertNotNull(findClientSubscriber(classes));
        assertNotNull(findRenderLayerClass(classes));
    }

    @Test
    @DisplayName("T3-10 (F3xF7xF8): Distribution JAR packages metadata, common + client classes, model, texture, langs, NBT")
    void t3_10_builtJarContainsCompleteRuntimeAndClientArtifacts() throws IOException {
        Path root = resolveProjectRoot();
        Path jarPath = findProductionJar(root);
        List<E2EClassfileInspector.ClassInfo> jarClasses = loadJarClasses(jarPath);
        assertNotNull(findModEntrypoint(jarClasses));
        assertNotNull(findClientSubscriber(jarClasses));
        assertNotNull(findRenderLayerClass(jarClasses));
        f3_t1_04_productionJarCompleteness();
    }

    // =========================================================================
    // Tier 4 — Clean Production JAR & Determinism Audit (T4-06)
    // =========================================================================

    @Test
    @DisplayName("T4-06 (F1,F2,F3,F7,F8): Clean production JAR audit excludes -Pe2e, passes Java 21 & negative checks")
    void t4_06_cleanProductionJarAndDeterminismAudit() throws IOException {
        f3_t2_03_productionJarHygiene();
        f3_t2_01_bytecodeMajorVersionJava21();
        f3_t2_05_zeroLegacyMandatoryOr1212Symbols();
        f1_t2_05_negativeSelfCheckRejectsBadDocs();
        f2_t2_01_modIdRegexBoundaries();
        f7_t2_02_negativeModelValidatorSelfCheck();
        f7_t2_03_negativeTextureValidatorSelfCheck();
    }

    // =========================================================================
    // Internal Helpers
    // =========================================================================

    private static E2EClassfileInspector.MethodInfo findRenderMethod(E2EClassfileInspector.ClassInfo layer) {
        return layer.methods().stream()
                .filter(m -> "render".equals(m.name())
                        && m.descriptor().contains("Lcom/mojang/blaze3d/vertex/PoseStack;")
                        && m.descriptor().contains("Lnet/minecraft/client/player/AbstractClientPlayer;"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        layer.binaryName() + " must override RenderLayer#render with AbstractClientPlayer"));
    }

    private static E2EClassfileInspector.MethodInfo findSameClassMethod(
            E2EClassfileInspector.ClassInfo ownerClass,
            E2EClassfileInspector.MemberRef ref
    ) {
        if (ref == null || !ownerClass.internalName().equals(ref.owner())) {
            return null;
        }
        for (E2EClassfileInspector.MethodInfo m : ownerClass.methods()) {
            if (m.name().equals(ref.name()) && m.descriptor().equals(ref.descriptor())) {
                return m;
            }
        }
        return null;
    }

    private static void collectTransitiveRefs(
            E2EClassfileInspector.ClassInfo ownerClass,
            E2EClassfileInspector.MethodInfo method,
            Set<String> visited,
            List<E2EClassfileInspector.MemberRef> outRefs
    ) {
        String key = method.name() + method.descriptor();
        if (!visited.add(key)) {
            return;
        }
        for (E2EClassfileInspector.Instruction inst : method.instructions()) {
            E2EClassfileInspector.MemberRef ref = inst.memberRef();
            if (ref != null) {
                outRefs.add(ref);
                E2EClassfileInspector.MethodInfo helper = findSameClassMethod(ownerClass, ref);
                if (helper != null) {
                    collectTransitiveRefs(ownerClass, helper, visited, outRefs);
                }
            }
        }
    }

    private static boolean hasFeetOrSlotCheckTransitive(
            E2EClassfileInspector.ClassInfo ownerClass,
            E2EClassfileInspector.MethodInfo method
    ) {
        List<E2EClassfileInspector.MemberRef> refs = new ArrayList<>();
        collectTransitiveRefs(ownerClass, method, new HashSet<>(), refs);
        return refs.stream().anyMatch(r ->
                r.name().equals("getItemBySlot")
                        || (r.owner().equals("net/minecraft/world/entity/EquipmentSlot") && r.name().equals("FEET")));
    }

    private enum PoseTraceOp {
        PUSH,
        RENDER_STATIC,
        POP
    }

    private static void assertPoseStackSymmetryAndZeroRawGl(
            E2EClassfileInspector.ClassInfo layer,
            E2EClassfileInspector.MethodInfo renderMethod
    ) {
        List<PoseTraceOp> trace = new ArrayList<>();
        boolean[] sawExceptionHandler = new boolean[1];
        collectPoseTrace(layer, renderMethod, new HashSet<>(), trace, sawExceptionHandler);

        long pushCount = 0;
        long popCount = 0;
        int earliestPush = Integer.MAX_VALUE;
        int earliestRenderStatic = Integer.MAX_VALUE;
        int earliestPop = Integer.MAX_VALUE;

        for (int i = 0; i < trace.size(); i++) {
            switch (trace.get(i)) {
                case PUSH -> {
                    pushCount++;
                    earliestPush = Math.min(earliestPush, i);
                }
                case POP -> {
                    popCount++;
                    earliestPop = Math.min(earliestPop, i);
                }
                case RENDER_STATIC -> earliestRenderStatic = Math.min(earliestRenderStatic, i);
            }
        }

        assertTrue(pushCount >= 1, "RenderLayer.render must call PoseStack.pushPose()");
        assertTrue(popCount >= pushCount,
                "popPose() count (" + popCount + ") must be >= pushPose() count (" + pushCount + ")");
        if (!sawExceptionHandler[0]) {
            assertEquals(pushCount, popCount,
                    "Without try/finally exception handlers, pushPose() count must equal popPose() count");
        }
        assertTrue(earliestRenderStatic < Integer.MAX_VALUE, "RenderLayer.render must call ItemRenderer.renderStatic(...)");
        assertTrue(earliestPush < earliestRenderStatic && earliestRenderStatic < earliestPop,
                "Expected ordering pushPose (" + earliestPush + ") < renderStatic (" + earliestRenderStatic
                        + ") < popPose (" + earliestPop + ")");

        for (String ref : layer.classConstants()) {
            assertFalse(ref.startsWith("org/lwjgl/") || ref.contains("GL11"),
                    "RenderLayer must not invoke raw LWJGL/GL11: " + ref);
        }
        for (E2EClassfileInspector.MemberRef ref : layer.methodRefs()) {
            assertFalse(ref.owner().startsWith("org/lwjgl/") || ref.owner().contains("GL11"),
                    "RenderLayer must not invoke raw LWJGL/GL11 method: " + ref.owner() + "." + ref.name());
        }
        for (E2EClassfileInspector.MemberRef ref : layer.fieldRefs()) {
            assertFalse(ref.owner().startsWith("org/lwjgl/") || ref.owner().contains("GL11"),
                    "RenderLayer must not access raw LWJGL/GL11 field: " + ref.owner() + "." + ref.name());
        }
        for (String utf8 : layer.utf8Constants()) {
            assertFalse(utf8.startsWith("org/lwjgl/") || utf8.contains("Lorg/lwjgl/") || utf8.contains("GL11"),
                    "RenderLayer must not reference raw LWJGL/GL11 symbol: " + utf8);
        }
    }

    private static void collectPoseTrace(
            E2EClassfileInspector.ClassInfo layer,
            E2EClassfileInspector.MethodInfo method,
            Set<String> activeStack,
            List<PoseTraceOp> outTrace,
            boolean[] sawExceptionHandler
    ) {
        String key = method.name() + method.descriptor();
        if (!activeStack.add(key)) {
            return;
        }
        try {
            int traceStart = outTrace.size();
            for (E2EClassfileInspector.Instruction inst : method.instructions()) {
                E2EClassfileInspector.MemberRef r = inst.memberRef();
                if (r == null) {
                    continue;
                }
                if (r.owner().equals("com/mojang/blaze3d/vertex/PoseStack") && r.name().equals("pushPose")) {
                    outTrace.add(PoseTraceOp.PUSH);
                } else if (r.owner().equals("com/mojang/blaze3d/vertex/PoseStack") && r.name().equals("popPose")) {
                    outTrace.add(PoseTraceOp.POP);
                } else if (isRenderStaticRef(r)) {
                    outTrace.add(PoseTraceOp.RENDER_STATIC);
                } else {
                    E2EClassfileInspector.MethodInfo helper = findSameClassMethod(layer, r);
                    if (helper != null) {
                        collectPoseTrace(layer, helper, activeStack, outTrace, sawExceptionHandler);
                    }
                }
            }
            if (method.hasExceptionHandlers() && outTrace.size() > traceStart) {
                sawExceptionHandler[0] = true;
            }
        } finally {
            activeStack.remove(key);
        }
    }

    private static boolean isRenderStaticRef(E2EClassfileInspector.MemberRef ref) {
        return ref != null
                && ref.owner().equals("net/minecraft/client/renderer/entity/ItemRenderer")
                && ref.name().equals("renderStatic");
    }

    private static boolean hasRenderStaticTransitive(
            E2EClassfileInspector.ClassInfo ownerClass,
            E2EClassfileInspector.MethodInfo method
    ) {
        List<E2EClassfileInspector.MemberRef> refs = new ArrayList<>();
        collectTransitiveRefs(ownerClass, method, new HashSet<>(), refs);
        return refs.stream().anyMatch(E2EComplianceTests::isRenderStaticRef);
    }

    private static boolean hasConditionalFeetCheckBeforeRenderStatic(
            E2EClassfileInspector.ClassInfo layer,
            E2EClassfileInspector.MethodInfo method
    ) {
        return hasConditionalFeetCheckBeforeRenderStatic(layer, method, new HashSet<>());
    }

    private static boolean hasConditionalFeetCheckBeforeRenderStatic(
            E2EClassfileInspector.ClassInfo layer,
            E2EClassfileInspector.MethodInfo method,
            Set<String> visited
    ) {
        String key = method.name() + method.descriptor();
        if (!visited.add(key)) {
            return false;
        }
        int firstFeetOrSlotOffset = Integer.MAX_VALUE;
        int firstBranchAfterCheck = Integer.MAX_VALUE;

        for (E2EClassfileInspector.Instruction inst : method.instructions()) {
            E2EClassfileInspector.MemberRef ref = inst.memberRef();
            if (ref != null) {
                boolean directCheck = ref.name().equals("getItemBySlot")
                        || (ref.owner().equals("net/minecraft/world/entity/EquipmentSlot") && ref.name().equals("FEET"));
                boolean helperCheck = false;
                if (!directCheck) {
                    E2EClassfileInspector.MethodInfo helper = findSameClassMethod(layer, ref);
                    if (helper != null && hasFeetOrSlotCheckTransitive(layer, helper)) {
                        helperCheck = true;
                    }
                }
                if (directCheck || helperCheck) {
                    firstFeetOrSlotOffset = Math.min(firstFeetOrSlotOffset, inst.offset());
                }
            }
            if (inst.offset() > firstFeetOrSlotOffset && inst.isConditionalBranch()) {
                firstBranchAfterCheck = Math.min(firstBranchAfterCheck, inst.offset());
            }
        }

        if (firstFeetOrSlotOffset < Integer.MAX_VALUE && firstBranchAfterCheck < Integer.MAX_VALUE) {
            for (E2EClassfileInspector.Instruction inst : method.instructions()) {
                if (inst.offset() <= firstBranchAfterCheck) {
                    continue;
                }
                E2EClassfileInspector.MemberRef ref = inst.memberRef();
                if (isRenderStaticRef(ref)) {
                    return true;
                }
                E2EClassfileInspector.MethodInfo helper = findSameClassMethod(layer, ref);
                if (helper != null && hasRenderStaticTransitive(layer, helper)) {
                    return true;
                }
            }
        }

        for (E2EClassfileInspector.Instruction inst : method.instructions()) {
            E2EClassfileInspector.MethodInfo helper = findSameClassMethod(layer, inst.memberRef());
            if (helper != null && hasConditionalFeetCheckBeforeRenderStatic(layer, helper, visited)) {
                return true;
            }
        }
        return false;
    }

    private static String buildSyntheticModel(int count, double angle, String texRef) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"textures\":{\"t\":\"xianxia_placeholder:item/qingfeng_flying_sword\"},\"elements\":[");
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"from\":[7,0,7],\"to\":[9,2,9]");
            if (angle != 0.0 && i == 0) {
                sb.append(",\"rotation\":{\"origin\":[8,1,8],\"axis\":\"z\",\"angle\":").append(angle).append('}');
            }
            sb.append(",\"faces\":{\"north\":{\"uv\":[0,0,4,4],\"texture\":\"").append(texRef).append("\"}}}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private static BufferedImage makeSolidImage(int w, int h, int argb) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, argb);
            }
        }
        return img;
    }

    private static byte[] encodePng(BufferedImage img) throws IOException {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        ImageIO.write(img, "PNG", baos);
        return baos.toByteArray();
    }
}
