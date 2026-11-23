package com.example.xianxia.e2e;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reusable opaque-box validators for F1 docs, F2 metadata identifiers, and F7 3D model/texture contracts.
 * Shared by positive Tier-1/Tier-2/Tier-3 tests and deterministic negative self-tests.
 */
final class E2EComplianceValidators {

    static final Pattern MOD_ID_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{1,63}$");
    static final Pattern MARKDOWN_LINK_PATTERN = Pattern.compile("(?<!!)\\[[^\\]]+\\]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)");
    static final List<String> REQUIRED_DISPLAY_CONTEXTS = List.of(
            "gui",
            "ground",
            "fixed",
            "thirdperson_righthand",
            "thirdperson_lefthand",
            "firstperson_righthand",
            "firstperson_lefthand"
    );

    private static final Set<String> FLAT_OR_BLOCK_PARENTS = Set.of(
            "minecraft:item/generated",
            "item/generated",
            "minecraft:item/handheld",
            "item/handheld",
            "minecraft:block/cube_all",
            "block/cube_all"
    );

    private static final Set<Double> VALID_ROTATION_ANGLES = Set.of(-45.0, -22.5, 0.0, 22.5, 45.0);
    private static final Set<String> VALID_ROTATION_AXES = Set.of("x", "y", "z");

    private E2EComplianceValidators() {
    }

    static String decodeStrictUtf8(byte[] bytes, String label) {
        try {
            String decoded = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            if (decoded.indexOf('\uFFFD') >= 0) {
                throw new IllegalArgumentException(label + " contains U+FFFD replacement character");
            }
            return decoded;
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException(label + " is not valid UTF-8: " + e.getMessage(), e);
        }
    }

    static boolean isValidModId(String modId) {
        return modId != null && MOD_ID_PATTERN.matcher(modId).matches();
    }

    static void validateDocsContent(
            String readmeText,
            String guideText,
            String licenseText,
            Path projectRoot,
            Path readmeBaseDir,
            Path guideBaseDir
    ) {
        if (readmeText == null || readmeText.getBytes(StandardCharsets.UTF_8).length < 1500) {
            throw new IllegalArgumentException("README.md must exist and be at least 1500 bytes");
        }
        if (guideText == null || guideText.getBytes(StandardCharsets.UTF_8).length < 1500) {
            throw new IllegalArgumentException("docs/DEVELOPMENT_GUIDE.md must exist and be at least 1500 bytes");
        }
        if (licenseText == null || licenseText.getBytes(StandardCharsets.UTF_8).length < 200) {
            throw new IllegalArgumentException("LICENSE must exist and be at least 200 bytes");
        }

        if (!readmeText.contains("1.21.1") || !readmeText.toLowerCase(Locale.ROOT).contains("neoforge")) {
            throw new IllegalArgumentException("README.md must state Minecraft 1.21.1 and NeoForge");
        }
        boolean hasXianxia = readmeText.contains("\u4FEE\u4ED9") || readmeText.toLowerCase(Locale.ROOT).contains("xianxia");
        boolean hasFlyingSword = readmeText.contains("\u5FA1\u5251")
                || readmeText.contains("\u98DE\u5251")
                || readmeText.toLowerCase(Locale.ROOT).contains("flying sword");
        if (!hasXianxia || !hasFlyingSword) {
            throw new IllegalArgumentException("README.md must describe Chinese Xianxia cultivation and Flying Sword positioning");
        }

        validateRelativeLinks(readmeText, readmeBaseDir, "README.md");
        validateRelativeLinks(guideText, guideBaseDir, "docs/DEVELOPMENT_GUIDE.md");
    }

    static void validateRelativeLinks(String markdown, Path baseDir, String sourceName) {
        Matcher matcher = MARKDOWN_LINK_PATTERN.matcher(markdown);
        while (matcher.find()) {
            String target = matcher.group(1).trim();
            if (target.isEmpty()
                    || target.startsWith("http://")
                    || target.startsWith("https://")
                    || target.startsWith("mailto:")
                    || target.startsWith("#")) {
                continue;
            }
            int hashIdx = target.indexOf('#');
            String cleanTarget = hashIdx >= 0 ? target.substring(0, hashIdx) : target;
            if (cleanTarget.isEmpty()) {
                continue;
            }
            Path resolved = baseDir.resolve(cleanTarget).normalize();
            if (!Files.exists(resolved)) {
                throw new IllegalArgumentException("Broken relative Markdown link in " + sourceName + ": " + target + " -> " + resolved);
            }
        }
    }

    static void validateModelJson(
            String jsonText,
            Function<String, BufferedImage> textureLoader,
            boolean checkGeometry,
            boolean checkDisplay,
            boolean checkUvCoverage
    ) {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(jsonText);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid model JSON syntax: " + e.getMessage(), e);
        }
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("Model JSON root must be an object");
        }
        JsonObject root = parsed.getAsJsonObject();

        if (root.has("parent") && root.get("parent").isJsonPrimitive()) {
            String parent = root.get("parent").getAsString().trim();
            if (FLAT_OR_BLOCK_PARENTS.contains(parent)) {
                throw new IllegalArgumentException("Model must not use flat/block parent: " + parent);
            }
        }

        if (!root.has("textures") || !root.get("textures").isJsonObject() || root.getAsJsonObject("textures").size() == 0) {
            throw new IllegalArgumentException("Model must define non-empty 'textures' map");
        }
        JsonObject texturesObj = root.getAsJsonObject("textures");

        if (!root.has("elements") || !root.get("elements").isJsonArray()) {
            throw new IllegalArgumentException("Model must define 'elements' JsonArray");
        }
        JsonArray elements = root.getAsJsonArray("elements");
        if (elements.size() < 12) {
            throw new IllegalArgumentException("Multi-part 3D Xianxia sword requires >= 12 elements, got " + elements.size());
        }

        double minX = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        Set<String> dimensionTiers = new HashSet<>();

        for (int i = 0; i < elements.size(); i++) {
            JsonElement elem = elements.get(i);
            if (!elem.isJsonObject()) {
                throw new IllegalArgumentException("Element[" + i + "] is not a JSON object");
            }
            JsonObject obj = elem.getAsJsonObject();
            double[] from = readVec3(obj, "from", i);
            double[] to = readVec3(obj, "to", i);
            for (int axis = 0; axis < 3; axis++) {
                if (from[axis] > to[axis]) {
                    throw new IllegalArgumentException("Element[" + i + "] has from[" + axis + "] > to[" + axis + "]");
                }
                if (from[axis] < -16.0 || from[axis] > 32.0 || to[axis] < -16.0 || to[axis] > 32.0) {
                    throw new IllegalArgumentException("Element[" + i + "] coordinate out of [-16, 32] bounds");
                }
            }
            minX = Math.min(minX, from[0]);
            maxX = Math.max(maxX, to[0]);
            minY = Math.min(minY, from[1]);
            maxY = Math.max(maxY, to[1]);
            minZ = Math.min(minZ, from[2]);
            maxZ = Math.max(maxZ, to[2]);
            double dx = to[0] - from[0];
            double dy = to[1] - from[1];
            double dz = to[2] - from[2];
            dimensionTiers.add(String.format(Locale.ROOT, "%.2fx%.2fx%.2f", dx, dy, dz));

            if (obj.has("rotation")) {
                if (!obj.get("rotation").isJsonObject()) {
                    throw new IllegalArgumentException("Element[" + i + "] rotation must be object");
                }
                JsonObject rot = obj.getAsJsonObject("rotation");
                if (rot.has("angle")) {
                    double angle = rot.get("angle").getAsDouble();
                    if (angle == 0.0) {
                        angle = 0.0;
                    }
                    if (!VALID_ROTATION_ANGLES.contains(angle)) {
                        throw new IllegalArgumentException("Element[" + i + "] has invalid rotation angle: " + angle);
                    }
                }
                if (rot.has("axis")) {
                    String axis = rot.get("axis").getAsString();
                    if (!VALID_ROTATION_AXES.contains(axis)) {
                        throw new IllegalArgumentException("Element[" + i + "] has invalid rotation axis: " + axis);
                    }
                }
            }

            if (!obj.has("faces") || !obj.get("faces").isJsonObject() || obj.getAsJsonObject("faces").size() == 0) {
                throw new IllegalArgumentException("Element[" + i + "] must define faces");
            }
            JsonObject faces = obj.getAsJsonObject("faces");
            for (Map.Entry<String, JsonElement> entry : faces.entrySet()) {
                JsonObject face = entry.getValue().getAsJsonObject();
                if (!face.has("texture")) {
                    throw new IllegalArgumentException("Element[" + i + "] face " + entry.getKey() + " missing texture");
                }
                String texRef = face.get("texture").getAsString();
                if (!texRef.startsWith("#")) {
                    throw new IllegalArgumentException("Face texture must reference #key: " + texRef);
                }
                String resolvedTex = resolveTextureRef(texturesObj, texRef);
                BufferedImage img = textureLoader.apply(resolvedTex);
                if (img == null) {
                    throw new IllegalArgumentException("Missing PNG texture for " + texRef + " -> " + resolvedTex);
                }

                if (face.has("uv")) {
                    JsonArray uvArr = face.getAsJsonArray("uv");
                    if (uvArr.size() != 4) {
                        throw new IllegalArgumentException("Element[" + i + "] face " + entry.getKey() + " uv must have 4 numbers");
                    }
                    double u1 = uvArr.get(0).getAsDouble();
                    double v1 = uvArr.get(1).getAsDouble();
                    double u2 = uvArr.get(2).getAsDouble();
                    double v2 = uvArr.get(3).getAsDouble();
                    for (double v : new double[]{u1, v1, u2, v2}) {
                        if (v < 0.0 || v > 16.0) {
                            throw new IllegalArgumentException("Element[" + i + "] face uv out of [0, 16]: " + v);
                        }
                    }
                    if (checkUvCoverage) {
                        assertUvCoversOpaquePixel(img, u1, v1, u2, v2, i, entry.getKey());
                    }
                }
            }
        }

        if (checkGeometry) {
            double[] spans = new double[]{maxX - minX, maxY - minY, maxZ - minZ};
            Arrays.sort(spans);
            if (spans[2] < 12.0) {
                throw new IllegalArgumentException("Primary sword length span must be >= 12.0, got " + spans[2]);
            }
            if (spans[1] < 3.5) {
                throw new IllegalArgumentException("Crossguard width span must be >= 3.5, got " + spans[1]);
            }
            if (spans[0] < 1.2) {
                throw new IllegalArgumentException("3D depth span must be >= 1.2, got " + spans[0]);
            }
            if (dimensionTiers.size() < 4) {
                throw new IllegalArgumentException("Expected >= 4 distinct width/depth tiers across sword geometry, got " + dimensionTiers.size());
            }
        }

        if (checkDisplay) {
            if (!root.has("display") || !root.get("display").isJsonObject()) {
                throw new IllegalArgumentException("Model must define 'display' transforms");
            }
            JsonObject display = root.getAsJsonObject("display");
            for (String ctx : REQUIRED_DISPLAY_CONTEXTS) {
                if (!display.has(ctx) || !display.get(ctx).isJsonObject()) {
                    throw new IllegalArgumentException("Model 'display' missing context: " + ctx);
                }
                JsonObject ctxObj = display.getAsJsonObject(ctx);
                if (ctxObj.has("rotation")) {
                    validateFloatArray(ctxObj.get("rotation"), 3, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, ctx + ".rotation");
                }
                if (ctxObj.has("translation")) {
                    validateFloatArray(ctxObj.get("translation"), 3, -80.0, 80.0, ctx + ".translation");
                }
                if (ctxObj.has("scale")) {
                    double[] sc = validateFloatArray(ctxObj.get("scale"), 3, 0.0, 4.0, ctx + ".scale");
                    for (double v : sc) {
                        if (v <= 0.0 || v > 4.0) {
                            throw new IllegalArgumentException(ctx + ".scale must be in (0, 4], got " + v);
                        }
                    }
                }
            }
        }
    }

    static void validateTexturePng(byte[] rawBytes, BufferedImage img) {
        if (rawBytes.length < 8
                || (rawBytes[0] & 0xFF) != 0x89
                || rawBytes[1] != 'P'
                || rawBytes[2] != 'N'
                || rawBytes[3] != 'G') {
            throw new IllegalArgumentException("Invalid PNG signature");
        }
        if (img == null) {
            throw new IllegalArgumentException("Failed to decode PNG image");
        }
        int w = img.getWidth();
        int h = img.getHeight();
        if (w < 16 || h < 16 || w != h || Integer.bitCount(w) != 1) {
            throw new IllegalArgumentException("Texture must be square power-of-two >= 16x16, got " + w + "x" + h);
        }
        if (!img.getColorModel().hasAlpha()) {
            throw new IllegalArgumentException("Texture PNG must support alpha channel");
        }

        Set<Integer> distinctColors = new HashSet<>();
        List<int[]> rgbClusters = new ArrayList<>();
        boolean hasTransparent = false;
        boolean hasOpaque = false;
        boolean hasCoolTone = false;
        boolean hasWarmTone = false;

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = img.getRGB(x, y);
                int a = (argb >>> 24) & 0xFF;
                int r = (argb >>> 16) & 0xFF;
                int g = (argb >>> 8) & 0xFF;
                int b = argb & 0xFF;
                if (a == 0) {
                    hasTransparent = true;
                } else {
                    if (distinctColors.add(argb)) {
                        boolean closeToExisting = false;
                        for (int[] c : rgbClusters) {
                            int dr = r - c[0];
                            int dg = g - c[1];
                            int db = b - c[2];
                            if (dr * dr + dg * dg + db * db < 400) {
                                closeToExisting = true;
                                break;
                            }
                        }
                        if (!closeToExisting) {
                            rgbClusters.add(new int[]{r, g, b});
                        }
                    }
                    if (a > 200) {
                        hasOpaque = true;
                    }
                    if ((b - r >= 6) || (g - r >= 6)) {
                        hasCoolTone = true;
                    }
                    if (r - b >= 15) {
                        hasWarmTone = true;
                    }
                }
            }
        }

        if (!hasTransparent || !hasOpaque) {
            throw new IllegalArgumentException("Texture must contain both transparent (alpha==0) and opaque (alpha>200) pixels");
        }
        if (distinctColors.size() < 12) {
            throw new IllegalArgumentException("Texture must have >= 12 distinct non-transparent RGBA colors, got " + distinctColors.size());
        }
        if (rgbClusters.size() < 5) {
            throw new IllegalArgumentException("Texture color spread is too narrow (only " + rgbClusters.size() + " distinct RGB clusters at Euclidean distance >= 20)");
        }
        if (!hasCoolTone || !hasWarmTone) {
            throw new IllegalArgumentException("Texture must exhibit multi-hue Xianxia contrast (cool celadon/steel AND warm gold/bronze/crimson)");
        }
    }

    private static String resolveTextureRef(JsonObject texturesObj, String startRef) {
        String current = startRef;
        for (int depth = 0; depth < 8 && current.startsWith("#"); depth++) {
            String key = current.substring(1);
            if (!texturesObj.has(key)) {
                throw new IllegalArgumentException("Dangling texture key reference: " + current);
            }
            current = texturesObj.get(key).getAsString();
        }
        if (current.startsWith("#")) {
            throw new IllegalArgumentException("Cyclic or overly deep texture reference: " + startRef);
        }
        return current;
    }

    private static void assertUvCoversOpaquePixel(
            BufferedImage img,
            double u1,
            double v1,
            double u2,
            double v2,
            int elemIndex,
            String faceName
    ) {
        int w = img.getWidth();
        int h = img.getHeight();
        double minU = Math.min(u1, u2);
        double maxU = Math.max(u1, u2);
        double minV = Math.min(v1, v2);
        double maxV = Math.max(v1, v2);
        int x0 = Math.max(0, Math.min(w - 1, (int) Math.floor(minU * w / 16.0)));
        int x1 = Math.max(x0 + 1, Math.min(w, (int) Math.ceil(maxU * w / 16.0)));
        int y0 = Math.max(0, Math.min(h - 1, (int) Math.floor(minV * h / 16.0)));
        int y1 = Math.max(y0 + 1, Math.min(h, (int) Math.ceil(maxV * h / 16.0)));

        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int alpha = (img.getRGB(x, y) >>> 24) & 0xFF;
                if (alpha > 0) {
                    return;
                }
            }
        }
        throw new IllegalArgumentException("Element[" + elemIndex + "] face " + faceName
                + " UV [" + u1 + "," + v1 + "," + u2 + "," + v2 + "] maps to completely transparent PNG pixels");
    }

    private static double[] readVec3(JsonObject obj, String key, int index) {
        if (!obj.has(key) || !obj.get(key).isJsonArray()) {
            throw new IllegalArgumentException("Element[" + index + "] missing array '" + key + "'");
        }
        return validateFloatArray(obj.get(key), 3, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, "Element[" + index + "]." + key);
    }

    private static double[] validateFloatArray(JsonElement elem, int expectedLen, double min, double max, String label) {
        if (!elem.isJsonArray()) {
            throw new IllegalArgumentException(label + " must be a JSON array");
        }
        JsonArray arr = elem.getAsJsonArray();
        if (arr.size() != expectedLen) {
            throw new IllegalArgumentException(label + " must have length " + expectedLen);
        }
        double[] out = new double[expectedLen];
        for (int i = 0; i < expectedLen; i++) {
            out[i] = arr.get(i).getAsDouble();
            if (out[i] < min || out[i] > max) {
                throw new IllegalArgumentException(label + "[" + i + "] out of range [" + min + ", " + max + "]: " + out[i]);
            }
        }
        return out;
    }

    static List<String> extractCodePathsFromGuide(String guideText) {
        List<String> paths = new ArrayList<>();
        Matcher m = Pattern.compile("`((?:src|docs|README\\.md|LICENSE|build\\.gradle|settings\\.gradle|gradle\\.properties)[^`\\s]*)`").matcher(guideText);
        while (m.find()) {
            String candidate = m.group(1).replace('\\', '/');
            if (!candidate.contains("*")
                    && !candidate.contains("<")
                    && !candidate.contains(">")
                    && !candidate.contains("${")
                    && !candidate.contains("...")) {
                paths.add(candidate);
            }
        }
        return paths;
    }

    static byte[] readBytes(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read file: " + path, e);
        }
    }
}
