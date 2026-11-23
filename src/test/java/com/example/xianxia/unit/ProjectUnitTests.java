package com.example.xianxia.unit;

import com.example.xianxia.ModConstants;
import com.example.xianxia.XianxiaPlaceholderMod;
import com.example.xianxia.item.FlyingSwordItem;
import com.example.xianxia.registry.ModCreativeTabs;
import com.example.xianxia.registry.ModItems;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.Equipable;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForgeMod;
import net.neoforged.neoforgespi.language.IModInfo;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectUnitTests {

    private static Path resolveProjectRoot() {
        String configured = System.getProperty("xianxia.projectDir");
        if (configured != null && !configured.isBlank()) {
            Path root = Path.of(configured).toAbsolutePath().normalize();
            if (Files.exists(root.resolve("gradle.properties")) && Files.exists(root.resolve("settings.gradle"))) {
                return root;
            }
        }
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.exists(current.resolve("gradle.properties")) && Files.exists(current.resolve("settings.gradle"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to locate project root containing gradle.properties and settings.gradle");
    }

    @Test
    void modConstantsAreValidAndConsistent() {
        assertEquals("xianxia_placeholder", ModConstants.MOD_ID);
        assertTrue(ModConstants.MOD_ID.matches("^[a-z][a-z0-9_]{1,63}$"));
        assertEquals("Xianxia Cultivation Mod (Placeholder)", ModConstants.MOD_NAME);
        assertEquals("qingfeng_flying_sword", ModConstants.ITEM_QINGFENG_FLYING_SWORD);
        assertEquals(
                ResourceLocation.fromNamespaceAndPath("xianxia_placeholder", "flying_sword_flight"),
                ModConstants.FLIGHT_MODIFIER_ID
        );
        assertEquals("xianxia_placeholder:sample", ModConstants.id("sample").toString());
    }

    @Test
    void gradlePropertiesMatchesPlaceholderContract() throws IOException {
        Path propsPath = resolveProjectRoot().resolve("gradle.properties");
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(propsPath, StandardCharsets.UTF_8)) {
            props.load(reader);
        }

        assertEquals(ModConstants.MOD_ID, props.getProperty("mod_id"));
        assertEquals(ModConstants.MOD_NAME, props.getProperty("mod_name"));
        assertEquals(XianxiaPlaceholderMod.class.getPackageName(), props.getProperty("mod_group_id"));
        assertEquals("0.1.0-alpha", props.getProperty("mod_version"));
        assertEquals("Author Placeholder", props.getProperty("mod_authors"));
        assertEquals("MIT", props.getProperty("mod_license"));
        assertEquals("1.21.1", props.getProperty("minecraft_version"));
        String neoVersion = props.getProperty("neo_version");
        assertTrue(neoVersion != null && neoVersion.startsWith("21.1."), "Expected NeoForge 21.1.x, got " + neoVersion);
    }

    @Test
    void generatedModsTomlAndModListAreValid() throws IOException {
        Path tomlPath = resolveProjectRoot().resolve("build/generated/sources/modMetadata/META-INF/neoforge.mods.toml");
        assertTrue(Files.exists(tomlPath), "Generated neoforge.mods.toml missing at " + tomlPath);

        String toml = Files.readString(tomlPath, StandardCharsets.UTF_8);
        assertFalse(toml.contains("${"), "Unexpanded placeholder found in generated neoforge.mods.toml");
        assertTrue(toml.contains("modId=\"xianxia_placeholder\""));
        assertTrue(toml.contains("displayName=\"Xianxia Cultivation Mod (Placeholder)\""));
        assertTrue(toml.contains("version=\"0.1.0-alpha\""));
        assertTrue(toml.contains("license=\"MIT\""));
        assertTrue(toml.contains("authors=\"Author Placeholder\""));
        assertTrue(toml.contains("type=\"required\""));

        assertTrue(ModList.get().isLoaded(ModConstants.MOD_ID), "Mod must be loaded by FML ModList");
        ModContainer container = ModList.get().getModContainerById(ModConstants.MOD_ID).orElseThrow();
        IModInfo modInfo = container.getModInfo();
        assertEquals(ModConstants.MOD_ID, modInfo.getModId());
        assertEquals(ModConstants.MOD_NAME, modInfo.getDisplayName());
        assertEquals("0.1.0-alpha", modInfo.getVersion().toString());
    }

    @Test
    void registrySkeletonsMatchModNamespace() {
        assertEquals(ModConstants.MOD_ID, ModItems.ITEMS.getNamespace());
        assertEquals(ModConstants.MOD_ID, ModCreativeTabs.CREATIVE_MODE_TABS.getNamespace());
    }

    @Test
    void documentationAndLicenseMatchPlaceholders() throws IOException {
        Path root = resolveProjectRoot();
        Path readmePath = root.resolve("README.md");
        Path devGuidePath = root.resolve("docs/DEVELOPMENT_GUIDE.md");
        Path licensePath = root.resolve("LICENSE");

        assertTrue(Files.exists(readmePath), "README.md must exist");
        assertTrue(Files.exists(devGuidePath), "docs/DEVELOPMENT_GUIDE.md must exist");
        assertTrue(Files.exists(licensePath), "LICENSE must exist");

        String readme = Files.readString(readmePath, StandardCharsets.UTF_8);
        assertTrue(readme.contains("xianxia_placeholder"));
        assertTrue(readme.contains("com.example.xianxia"));
        assertTrue(readme.contains("Xianxia Cultivation Mod (Placeholder)"));
        assertTrue(readme.contains("Author Placeholder"));
        assertTrue(readme.contains("MIT"));

        String devGuide = Files.readString(devGuidePath, StandardCharsets.UTF_8);
        assertTrue(devGuide.contains("ModConstants"));
        assertTrue(devGuide.contains("XianxiaPlaceholderMod"));
        assertTrue(devGuide.contains("ModItems"));
        assertTrue(devGuide.contains("ModCreativeTabs"));

        String license = Files.readString(licensePath, StandardCharsets.UTF_8);
        assertTrue(license.contains("MIT License"));
        assertTrue(license.contains("Copyright (c) 2026 Author Placeholder"));
    }

    @Test
    void flyingSwordAndCreativeTabAreRegistered() {
        assertTrue(ModItems.QINGFENG_FLYING_SWORD.isBound(), "QINGFENG_FLYING_SWORD must be bound");
        FlyingSwordItem item = ModItems.QINGFENG_FLYING_SWORD.get();
        assertEquals(
                ModConstants.id(ModConstants.ITEM_QINGFENG_FLYING_SWORD),
                BuiltInRegistries.ITEM.getKey(item)
        );

        assertTrue(ModCreativeTabs.XIANXIA_TAB.isBound(), "XIANXIA_TAB must be bound");
        assertEquals(
                ModConstants.id("xianxia_tab"),
                BuiltInRegistries.CREATIVE_MODE_TAB.getKey(ModCreativeTabs.XIANXIA_TAB.get())
        );
    }

    @Test
    void flyingSwordSlotContractAndFlightAttribute() {
        FlyingSwordItem item = ModItems.QINGFENG_FLYING_SWORD.get();
        assertTrue(item instanceof Equipable, "FlyingSwordItem must implement Equipable");
        assertEquals(EquipmentSlot.FEET, item.getEquipmentSlot());

        ItemStack stack = new ItemStack(item);
        assertEquals(1, stack.getMaxStackSize());
        assertEquals(Rarity.RARE, stack.getRarity());
        assertTrue(item.canEquip(stack, EquipmentSlot.FEET, null));
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (slot != EquipmentSlot.FEET) {
                assertFalse(item.canEquip(stack, slot, null), "Must reject slot " + slot);
            }
        }

        AtomicInteger feetFlightModifiers = new AtomicInteger();
        stack.forEachModifier(EquipmentSlot.FEET, (attribute, modifier) -> {
            if (attribute.equals(NeoForgeMod.CREATIVE_FLIGHT)) {
                feetFlightModifiers.incrementAndGet();
                assertEquals(ModConstants.FLIGHT_MODIFIER_ID, modifier.id());
                assertEquals(1.0D, modifier.amount(), 1.0E-9D);
                assertEquals(AttributeModifier.Operation.ADD_VALUE, modifier.operation());
            }
        });
        assertEquals(1, feetFlightModifiers.get(), "Expected exactly 1 CREATIVE_FLIGHT modifier in FEET");

        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (slot != EquipmentSlot.FEET) {
                stack.forEachModifier(slot, (attribute, modifier) -> {
                    assertFalse(attribute.equals(NeoForgeMod.CREATIVE_FLIGHT), "Unexpected CREATIVE_FLIGHT in " + slot);
                });
            }
        }
    }

    @Test
    void m2LocalizationAndStructureNbtAreValid() throws IOException {
        Path root = resolveProjectRoot();
        Gson gson = new Gson();

        Map<String, JsonObject> langJsons = new HashMap<>();
        for (String lang : new String[]{"en_us", "zh_cn"}) {
            Path langPath = root.resolve("src/main/resources/assets/" + ModConstants.MOD_ID + "/lang/" + lang + ".json");
            assertTrue(Files.exists(langPath), "Missing lang file: " + langPath);
            try (Reader reader = Files.newBufferedReader(langPath, StandardCharsets.UTF_8)) {
                JsonObject json = gson.fromJson(reader, JsonObject.class);
                assertTrue(json.has("itemGroup." + ModConstants.MOD_ID + ".xianxia_tab"));
                assertTrue(json.has("item." + ModConstants.MOD_ID + "." + ModConstants.ITEM_QINGFENG_FLYING_SWORD));
                assertTrue(json.has("tooltip." + ModConstants.MOD_ID + "." + ModConstants.ITEM_QINGFENG_FLYING_SWORD));
                for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                    assertTrue(entry.getValue().isJsonPrimitive() && !entry.getValue().getAsString().isBlank(),
                            lang + ".json entry must be non-blank string: " + entry.getKey());
                }
                langJsons.put(lang, json);
            }
        }
        assertEquals(langJsons.get("en_us").keySet(), langJsons.get("zh_cn").keySet(),
                "en_us.json and zh_cn.json must define identical translation key sets");

        Path nbtPath = root.resolve("src/main/resources/data/" + ModConstants.MOD_ID + "/structure/empty3x3x3.nbt");
        assertTrue(Files.exists(nbtPath), "Missing structure NBT: " + nbtPath);
        try (InputStream in = Files.newInputStream(nbtPath)) {
            CompoundTag nbt = NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
            assertEquals(3955, nbt.getInt("DataVersion"));
            ListTag size = nbt.getList("size", Tag.TAG_INT);
            assertEquals(3, size.size());
            assertEquals(3, size.getInt(0));
            assertEquals(3, size.getInt(1));
            assertEquals(3, size.getInt(2));
            ListTag palette = nbt.getList("palette", Tag.TAG_COMPOUND);
            assertEquals(1, palette.size());
            assertEquals("minecraft:air", palette.getCompound(0).getString("Name"));
            ListTag blocks = nbt.getList("blocks", Tag.TAG_COMPOUND);
            assertEquals(27, blocks.size());
            assertEquals(0, nbt.getList("entities", Tag.TAG_COMPOUND).size());
        }
    }

    @Test
    void m3FlyingSwordModelJsonIsValid3DMultiPart() throws IOException {
        Path root = resolveProjectRoot();
        Path modelPath = root.resolve("src/main/resources/assets/" + ModConstants.MOD_ID
                + "/models/item/" + ModConstants.ITEM_QINGFENG_FLYING_SWORD + ".json");
        assertTrue(Files.exists(modelPath), "Missing 3D sword model JSON: " + modelPath);

        JsonObject model;
        try (Reader reader = Files.newBufferedReader(modelPath, StandardCharsets.UTF_8)) {
            model = new Gson().fromJson(reader, JsonObject.class);
        }
        assertNotNull(model, "Model JSON must parse as JsonObject");

        if (model.has("parent")) {
            String parent = model.get("parent").getAsString();
            assertFalse(
                    parent.equals("minecraft:item/generated")
                            || parent.equals("item/generated")
                            || parent.equals("minecraft:item/handheld")
                            || parent.equals("item/handheld"),
                    "Model must not use flat generated/handheld parent: " + parent
            );
        }

        assertTrue(model.has("textures") && model.get("textures").isJsonObject(), "Missing textures object");
        JsonObject textures = model.getAsJsonObject("textures");
        assertTrue(textures.has("sword") && !textures.get("sword").getAsString().isBlank());
        assertTrue(textures.has("particle") && !textures.get("particle").getAsString().isBlank());
        for (Map.Entry<String, JsonElement> entry : textures.entrySet()) {
            String texRef = entry.getValue().getAsString();
            ResourceLocation rl = ResourceLocation.parse(texRef);
            Path texFile = root.resolve("src/main/resources/assets/" + rl.getNamespace()
                    + "/textures/" + rl.getPath() + ".png");
            assertTrue(Files.exists(texFile), "Referenced texture missing on disk: " + texFile);
        }

        assertTrue(model.has("elements") && model.get("elements").isJsonArray(), "Missing elements array");
        JsonArray elements = model.getAsJsonArray("elements");
        assertTrue(elements.size() >= 12, "Expected >= 12 3D elements, found " + elements.size());

        Set<String> validFaces = Set.of("north", "south", "east", "west", "up", "down");
        double[] validAngles = {-45.0, -22.5, 0.0, 22.5, 45.0};
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;

        for (int i = 0; i < elements.size(); i++) {
            JsonObject elem = elements.get(i).getAsJsonObject();
            JsonArray from = elem.getAsJsonArray("from");
            JsonArray to = elem.getAsJsonArray("to");
            assertEquals(3, from.size(), "Element " + i + " from must have 3 coords");
            assertEquals(3, to.size(), "Element " + i + " to must have 3 coords");

            double[] f = new double[3];
            double[] t = new double[3];
            for (int axis = 0; axis < 3; axis++) {
                f[axis] = from.get(axis).getAsDouble();
                t[axis] = to.get(axis).getAsDouble();
                assertTrue(f[axis] >= -16.0 && f[axis] <= 32.0, "from out of [-16,32] in element " + i);
                assertTrue(t[axis] >= -16.0 && t[axis] <= 32.0, "to out of [-16,32] in element " + i);
                assertTrue(t[axis] - f[axis] > 1.0e-4, "Element " + i + " must be non-flat on axis " + axis);
            }
            minX = Math.min(minX, f[0]);
            minY = Math.min(minY, f[1]);
            minZ = Math.min(minZ, f[2]);
            maxX = Math.max(maxX, t[0]);
            maxY = Math.max(maxY, t[1]);
            maxZ = Math.max(maxZ, t[2]);

            if (elem.has("rotation")) {
                JsonObject rot = elem.getAsJsonObject("rotation");
                assertEquals(3, rot.getAsJsonArray("origin").size());
                String axis = rot.get("axis").getAsString();
                assertTrue(axis.equals("x") || axis.equals("y") || axis.equals("z"), "Invalid rotation axis: " + axis);
                double angle = rot.get("angle").getAsDouble();
                boolean validAngle = false;
                for (double allowed : validAngles) {
                    if (Math.abs(angle - allowed) < 1.0e-5) {
                        validAngle = true;
                        break;
                    }
                }
                assertTrue(validAngle, "Invalid rotation angle " + angle + " in element " + i);
            }

            JsonObject faces = elem.getAsJsonObject("faces");
            assertNotNull(faces);
            assertFalse(faces.isEmpty(), "Element " + i + " has empty faces");
            for (Map.Entry<String, JsonElement> faceEntry : faces.entrySet()) {
                assertTrue(validFaces.contains(faceEntry.getKey()), "Invalid face: " + faceEntry.getKey());
                JsonObject face = faceEntry.getValue().getAsJsonObject();
                String faceTex = face.get("texture").getAsString();
                assertTrue(faceTex.startsWith("#"), "Face texture must start with #: " + faceTex);
                assertTrue(textures.has(faceTex.substring(1)), "Unresolved texture key: " + faceTex);

                JsonArray uv = face.getAsJsonArray("uv");
                assertEquals(4, uv.size(), "Face UV must have 4 floats");
                double u1 = uv.get(0).getAsDouble();
                double v1 = uv.get(1).getAsDouble();
                double u2 = uv.get(2).getAsDouble();
                double v2 = uv.get(3).getAsDouble();
                assertTrue(u1 >= 0.0 && u1 <= 16.0 && u2 >= 0.0 && u2 <= 16.0);
                assertTrue(v1 >= 0.0 && v1 <= 16.0 && v2 >= 0.0 && v2 <= 16.0);
                assertTrue(Math.abs(u2 - u1) > 1.0e-4 && Math.abs(v2 - v1) > 1.0e-4, "Degenerate UV in element " + i);
            }
        }

        assertTrue(maxX - minX > 0.0 && maxY - minY > 0.0 && maxZ - minZ > 0.0);

        assertTrue(model.has("display") && model.get("display").isJsonObject(), "Missing display transforms");
        JsonObject display = model.getAsJsonObject("display");
        List<String> requiredContexts = List.of(
                "gui", "ground", "fixed",
                "thirdperson_righthand", "thirdperson_lefthand",
                "firstperson_righthand", "firstperson_lefthand"
        );
        for (String ctx : requiredContexts) {
            assertTrue(display.has(ctx) && display.get(ctx).isJsonObject(), "Missing display context: " + ctx);
        }
        assertTrue(display.has("head") && display.get("head").isJsonObject(), "Missing display context: head");
        for (Map.Entry<String, JsonElement> ctxEntry : display.entrySet()) {
            String ctx = ctxEntry.getKey();
            assertTrue(ctxEntry.getValue().isJsonObject(), "display." + ctx + " must be a JSON object");
            JsonObject transform = ctxEntry.getValue().getAsJsonObject();
            for (String vecKey : List.of("rotation", "translation", "scale")) {
                if (transform.has(vecKey)) {
                    JsonArray vec = transform.getAsJsonArray(vecKey);
                    assertEquals(3, vec.size(), ctx + "." + vecKey + " must have 3 components");
                    if (vecKey.equals("translation")) {
                        for (int k = 0; k < 3; k++) {
                            double t = vec.get(k).getAsDouble();
                            assertTrue(t >= -80.0 && t <= 80.0, ctx + ".translation out of [-80,80]: " + t);
                        }
                    } else if (vecKey.equals("scale")) {
                        for (int k = 0; k < 3; k++) {
                            double s = vec.get(k).getAsDouble();
                            assertTrue(s > 0.0 && s <= 4.0, ctx + ".scale out of (0,4]: " + s);
                        }
                    }
                }
            }
        }
    }

    @Test
    void m3FlyingSwordTexturePngAndUvCoverageAreValid() throws IOException {
        Path root = resolveProjectRoot();
        Path pngPath = root.resolve("src/main/resources/assets/" + ModConstants.MOD_ID
                + "/textures/item/" + ModConstants.ITEM_QINGFENG_FLYING_SWORD + ".png");
        Path modelPath = root.resolve("src/main/resources/assets/" + ModConstants.MOD_ID
                + "/models/item/" + ModConstants.ITEM_QINGFENG_FLYING_SWORD + ".json");
        assertTrue(Files.exists(pngPath), "Missing sword texture PNG: " + pngPath);

        BufferedImage image;
        try (InputStream in = Files.newInputStream(pngPath)) {
            image = ImageIO.read(in);
        }
        assertNotNull(image, "ImageIO failed to decode sword PNG");
        assertEquals(64, image.getWidth(), "Expected 64px width");
        assertEquals(64, image.getHeight(), "Expected 64px height");
        assertTrue(image.getColorModel().hasAlpha(), "Sword texture must support alpha channel");

        int visiblePixels = 0;
        Set<Integer> distinctRgb = new HashSet<>();
        int minLum = Integer.MAX_VALUE;
        int maxLum = Integer.MIN_VALUE;
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                int argb = image.getRGB(x, y);
                int alpha = (argb >>> 24) & 0xFF;
                if (alpha > 0) {
                    visiblePixels++;
                    int rgb = argb & 0x00FFFFFF;
                    distinctRgb.add(rgb);
                    int r = (rgb >>> 16) & 0xFF;
                    int g = (rgb >>> 8) & 0xFF;
                    int b = rgb & 0xFF;
                    int lum = r + g + b;
                    minLum = Math.min(minLum, lum);
                    maxLum = Math.max(maxLum, lum);
                }
            }
        }
        assertTrue(visiblePixels >= 128, "Expected >= 128 visible pixels, found " + visiblePixels);
        assertTrue(distinctRgb.size() >= 8, "Expected rich Xianxia palette (>= 8 RGB colors), found " + distinctRgb.size());
        assertTrue(maxLum - minLum > 32, "Expected non-monochrome shading spread");

        JsonObject model;
        try (Reader reader = Files.newBufferedReader(modelPath, StandardCharsets.UTF_8)) {
            model = new Gson().fromJson(reader, JsonObject.class);
        }
        JsonArray elements = model.getAsJsonArray("elements");
        for (int i = 0; i < elements.size(); i++) {
            JsonObject elem = elements.get(i).getAsJsonObject();
            String name = elem.has("name") ? elem.get("name").getAsString() : ("#" + i);
            JsonObject faces = elem.getAsJsonObject("faces");
            for (Map.Entry<String, JsonElement> faceEntry : faces.entrySet()) {
                JsonArray uv = faceEntry.getValue().getAsJsonObject().getAsJsonArray("uv");
                double u1 = uv.get(0).getAsDouble();
                double v1 = uv.get(1).getAsDouble();
                double u2 = uv.get(2).getAsDouble();
                double v2 = uv.get(3).getAsDouble();

                int px0 = Math.max(0, Math.min(63, (int) Math.floor(Math.min(u1, u2) * 4.0)));
                int px1 = Math.max(px0, Math.min(63, (int) Math.ceil(Math.max(u1, u2) * 4.0) - 1));
                int py0 = Math.max(0, Math.min(63, (int) Math.floor(Math.min(v1, v2) * 4.0)));
                int py1 = Math.max(py0, Math.min(63, (int) Math.ceil(Math.max(v1, v2) * 4.0) - 1));

                int totalPixels = 0;
                int opaquePixels = 0;
                for (int yy = py0; yy <= py1; yy++) {
                    for (int xx = px0; xx <= px1; xx++) {
                        totalPixels++;
                        int alpha = (image.getRGB(xx, yy) >>> 24) & 0xFF;
                        assertEquals(255, alpha,
                                "UV box on " + name + "." + faceEntry.getKey()
                                        + " has non-opaque pixel at (" + xx + "," + yy + "): alpha=" + alpha);
                        opaquePixels++;
                    }
                }
                assertTrue(totalPixels > 0 && opaquePixels == totalPixels,
                        "UV box on " + name + "." + faceEntry.getKey() + " must have 100% opaque coverage");
            }
        }
    }

    @Test
    void m3ClientRenderClassesAreHeadlessSafeAndCompliant() throws Exception {
        Class.forName("com.example.xianxia.client.ClientSetup", false, ProjectUnitTests.class.getClassLoader());

        ClassNode clientSetup = readClassNode("com/example/xianxia/client/ClientSetup");
        assertNotNull(clientSetup.visibleAnnotations, "ClientSetup must declare @EventBusSubscriber");
        AnnotationNode subscriberAnn = clientSetup.visibleAnnotations.stream()
                .filter(a -> a.desc.equals("Lnet/neoforged/fml/common/EventBusSubscriber;"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing @EventBusSubscriber on ClientSetup"));

        String modid = null;
        boolean hasDistClient = false;
        boolean hasOtherDist = false;
        int distCount = 0;
        boolean hasDeprecatedBus = false;
        if (subscriberAnn.values != null) {
            for (int i = 0; i + 1 < subscriberAnn.values.size(); i += 2) {
                String key = (String) subscriberAnn.values.get(i);
                Object val = subscriberAnn.values.get(i + 1);
                if (key.equals("modid")) {
                    modid = (String) val;
                } else if (key.equals("value") && val instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof String[] enumVal
                                && enumVal.length == 2
                                && enumVal[0].equals("Lnet/neoforged/api/distmarker/Dist;")) {
                            distCount++;
                            if (enumVal[1].equals("CLIENT")) {
                                hasDistClient = true;
                            } else {
                                hasOtherDist = true;
                            }
                        }
                    }
                } else if (key.equals("bus")) {
                    hasDeprecatedBus = true;
                }
            }
        }
        assertEquals(ModConstants.MOD_ID, modid, "@EventBusSubscriber modid mismatch");
        assertTrue(hasDistClient && !hasOtherDist && distCount == 1,
                "@EventBusSubscriber value must restrict exclusively to Dist.CLIENT");
        assertFalse(hasDeprecatedBus, "@EventBusSubscriber must omit deprecated bus= parameter");

        MethodNode addLayersMethod = clientSetup.methods.stream()
                .filter(m -> (m.access & Opcodes.ACC_STATIC) != 0
                        && m.desc.equals("(Lnet/neoforged/neoforge/client/event/EntityRenderersEvent$AddLayers;)V")
                        && m.visibleAnnotations != null
                        && m.visibleAnnotations.stream().anyMatch(a -> a.desc.equals("Lnet/neoforged/bus/api/SubscribeEvent;")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing static @SubscribeEvent onAddLayers(AddLayers)"));

        boolean callsGetSkins = false;
        boolean callsGetSkin = false;
        boolean refsRenderLayer = false;
        for (AbstractInsnNode insn : addLayersMethod.instructions) {
            if (insn instanceof MethodInsnNode mInsn) {
                if (mInsn.name.equals("getSkins")) {
                    callsGetSkins = true;
                } else if (mInsn.name.equals("getSkin")) {
                    callsGetSkin = true;
                }
                if (mInsn.owner.equals("com/example/xianxia/client/render/FlyingSwordRenderLayer")) {
                    refsRenderLayer = true;
                }
            } else if (insn instanceof TypeInsnNode tInsn
                    && tInsn.desc.equals("com/example/xianxia/client/render/FlyingSwordRenderLayer")) {
                refsRenderLayer = true;
            }
        }
        assertTrue(callsGetSkins && callsGetSkin && refsRenderLayer,
                "onAddLayers must iterate getSkins(), guard getSkin(), and attach FlyingSwordRenderLayer");

        // Inspect FlyingSwordRenderLayer via ASM ONLY (do NOT Class.forName it under Dist.DEDICATED_SERVER)
        ClassNode layerNode = readClassNode("com/example/xianxia/client/render/FlyingSwordRenderLayer");
        assertEquals("net/minecraft/client/renderer/entity/layers/RenderLayer", layerNode.superName);
        assertNotNull(layerNode.signature);
        assertTrue(layerNode.signature.contains("AbstractClientPlayer"));
        assertTrue(layerNode.signature.contains("PlayerModel"));

        boolean hasConstructor = layerNode.methods.stream().anyMatch(m ->
                (m.access & Opcodes.ACC_PUBLIC) != 0
                        && m.name.equals("<init>")
                        && m.desc.equals("(Lnet/minecraft/client/renderer/entity/RenderLayerParent;)V"));
        assertTrue(hasConstructor, "Missing public FlyingSwordRenderLayer(RenderLayerParent) constructor");

        MethodNode renderMethod = layerNode.methods.stream()
                .filter(m -> (m.access & Opcodes.ACC_PUBLIC) != 0
                        && m.name.equals("render")
                        && m.desc.equals("(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/player/AbstractClientPlayer;FFFFFF)V"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing RenderLayer#render override for AbstractClientPlayer"));

        boolean refsFlyingSword = false;
        boolean refsFeetSlot = false;
        boolean refsFixedDisplayContext = false;
        boolean refsFlyingAbility = false;
        boolean refsNoOverlay = false;
        boolean callsIsInvisible = false;
        boolean callsIsPassenger = false;
        boolean callsIsSleeping = false;
        boolean callsIsCrouching = false;
        boolean callsRenderStatic = false;
        int pushPoseCount = 0;
        int popPoseCount = 0;
        int firstPushIndex = Integer.MAX_VALUE;
        int renderStaticIndex = Integer.MAX_VALUE;
        int firstPopIndex = Integer.MAX_VALUE;
        int insnIndex = 0;
        for (AbstractInsnNode insn : renderMethod.instructions) {
            if (insn instanceof FieldInsnNode fInsn) {
                if (fInsn.owner.equals("com/example/xianxia/registry/ModItems")
                        && fInsn.name.equals("QINGFENG_FLYING_SWORD")) {
                    refsFlyingSword = true;
                } else if (fInsn.owner.equals("net/minecraft/world/entity/EquipmentSlot")
                        && fInsn.name.equals("FEET")) {
                    refsFeetSlot = true;
                } else if (fInsn.owner.equals("net/minecraft/world/item/ItemDisplayContext")
                        && fInsn.name.equals("FIXED")) {
                    refsFixedDisplayContext = true;
                } else if (fInsn.owner.equals("net/minecraft/world/entity/player/Abilities")
                        && fInsn.name.equals("flying")) {
                    refsFlyingAbility = true;
                } else if (fInsn.owner.equals("net/minecraft/client/renderer/texture/OverlayTexture")
                        && fInsn.name.equals("NO_OVERLAY")) {
                    refsNoOverlay = true;
                }
            } else if (insn instanceof MethodInsnNode mInsn) {
                if (mInsn.owner.equals("com/mojang/blaze3d/vertex/PoseStack") && mInsn.name.equals("pushPose")) {
                    pushPoseCount++;
                    firstPushIndex = Math.min(firstPushIndex, insnIndex);
                } else if (mInsn.owner.equals("com/mojang/blaze3d/vertex/PoseStack") && mInsn.name.equals("popPose")) {
                    popPoseCount++;
                    firstPopIndex = Math.min(firstPopIndex, insnIndex);
                } else if (mInsn.owner.equals("net/minecraft/client/renderer/entity/ItemRenderer")
                        && mInsn.name.equals("renderStatic")) {
                    callsRenderStatic = true;
                    renderStaticIndex = Math.min(renderStaticIndex, insnIndex);
                } else if (mInsn.name.equals("isInvisible")) {
                    callsIsInvisible = true;
                } else if (mInsn.name.equals("isPassenger")) {
                    callsIsPassenger = true;
                } else if (mInsn.name.equals("isSleeping")) {
                    callsIsSleeping = true;
                } else if (mInsn.name.equals("isCrouching")) {
                    callsIsCrouching = true;
                }
                assertFalse(mInsn.owner.contains("EntityRenderState") || mInsn.owner.endsWith("/ArmorItem"),
                        "Disallowed 1.21.2+ or ArmorItem reference: " + mInsn.owner);
            } else if (insn instanceof TypeInsnNode tInsn) {
                assertFalse(tInsn.desc.contains("EntityRenderState") || tInsn.desc.endsWith("/ArmorItem"),
                        "Disallowed 1.21.2+ or ArmorItem reference: " + tInsn.desc);
            }
            insnIndex++;
        }
        assertTrue(refsFlyingSword && refsFeetSlot && refsFixedDisplayContext && refsNoOverlay
                        && refsFlyingAbility && callsIsInvisible && callsIsPassenger && callsIsSleeping
                        && callsIsCrouching && callsRenderStatic,
                "FlyingSwordRenderLayer#render must check EquipmentSlot.FEET, QINGFENG_FLYING_SWORD, isInvisible, isPassenger, isSleeping, isCrouching, Abilities.flying, ItemDisplayContext.FIXED, OverlayTexture.NO_OVERLAY, and ItemRenderer#renderStatic");
        assertTrue(pushPoseCount >= 1, "FlyingSwordRenderLayer#render must call PoseStack#pushPose");
        assertTrue(popPoseCount >= pushPoseCount, "PoseStack#popPose count must be >= pushPose count");
        assertFalse(renderMethod.tryCatchBlocks == null || renderMethod.tryCatchBlocks.isEmpty(),
                "FlyingSwordRenderLayer#render must guard PoseStack push/pop with try/finally");
        assertTrue(firstPushIndex < renderStaticIndex && renderStaticIndex < firstPopIndex,
                "ItemRenderer#renderStatic must execute between PoseStack#pushPose and PoseStack#popPose");
    }

    @Test
    void commonAndServerClassesHaveZeroClientOrLwjglReferences() throws IOException {
        Path classesBase = resolveProjectRoot().resolve("build/classes/java/main/com/example/xianxia");
        assertTrue(Files.isDirectory(classesBase), "Missing compiled classes dir: " + classesBase);

        List<String> forbiddenPrefixes = List.of(
                "net/minecraft/client/",
                "net/neoforged/neoforge/client/",
                "com/mojang/blaze3d/",
                "org/lwjgl/",
                "com/example/xianxia/client/"
        );

        int scannedNonClientClasses = 0;
        try (Stream<Path> walk = Files.walk(classesBase)) {
            List<Path> classFiles = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".class"))
                    .toList();
            for (Path classFile : classFiles) {
                ClassNode node = new ClassNode();
                new ClassReader(Files.readAllBytes(classFile)).accept(node, 0);
                if (node.name.startsWith("com/example/xianxia/client/")) {
                    continue;
                }
                scannedNonClientClasses++;
                Set<String> referencedTypes = collectReferencedInternalNames(node);
                for (String ref : referencedTypes) {
                    for (String forbidden : forbiddenPrefixes) {
                        assertFalse(ref.startsWith(forbidden),
                                "Non-client class " + node.name + " references forbidden type " + ref);
                    }
                }
            }
        }
        assertTrue(scannedNonClientClasses >= 6, "Expected >= 6 non-client classes scanned, got " + scannedNonClientClasses);
    }

    @Test
    void flyingSwordTooltipAndEquipSoundAndAttributeEdgeContract() {
        FlyingSwordItem item = ModItems.QINGFENG_FLYING_SWORD.get();
        assertEquals(SoundEvents.ARMOR_EQUIP_IRON.value(), item.getEquipSound().value(),
                "FlyingSwordItem equip sound must be ARMOR_EQUIP_IRON");

        ItemStack stack = new ItemStack(item);
        assertFalse(item.canEquip(stack, null, null), "canEquip must return false when armorType is null");

        List<Component> directLines = new ArrayList<>();
        item.appendHoverText(stack, Item.TooltipContext.EMPTY, directLines, TooltipFlag.NORMAL);
        assertTooltipHasExpectedLine(directLines, "appendHoverText");

        List<Component> stackLines = stack.getTooltipLines(Item.TooltipContext.EMPTY, null, TooltipFlag.NORMAL);
        assertTooltipHasExpectedLine(stackLines, "ItemStack#getTooltipLines");

        ItemStack stripped = stack.copy();
        stripped.set(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        AtomicInteger strippedFlightCount = new AtomicInteger();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            stripped.forEachModifier(slot, (attribute, modifier) -> {
                if (attribute.equals(NeoForgeMod.CREATIVE_FLIGHT)) {
                    strippedFlightCount.incrementAndGet();
                }
            });
        }
        assertEquals(0, strippedFlightCount.get(),
                "Sword stack with ItemAttributeModifiers.EMPTY must have zero CREATIVE_FLIGHT modifiers in any slot");
    }

    private static void assertTooltipHasExpectedLine(List<Component> lines, String sourceLabel) {
        String expectedKey = "tooltip." + ModConstants.MOD_ID + "." + ModConstants.ITEM_QINGFENG_FLYING_SWORD;
        TextColor expectedColor = TextColor.fromLegacyFormat(ChatFormatting.AQUA);
        boolean found = lines.stream().anyMatch(comp ->
                comp.getContents() instanceof TranslatableContents tc
                        && expectedKey.equals(tc.getKey())
                        && expectedColor != null
                        && expectedColor.equals(comp.getStyle().getColor()));
        assertTrue(found, sourceLabel + " must include TranslatableContents '" + expectedKey + "' styled AQUA");
    }

    private static Set<String> collectReferencedInternalNames(ClassNode node) {
        Set<String> refs = new HashSet<>();
        addTypeDescriptorRefs(node.superName == null ? null : Type.getObjectType(node.superName), refs);
        if (node.interfaces != null) {
            for (String iface : node.interfaces) {
                refs.add(iface);
            }
        }
        if (node.outerClass != null) {
            refs.add(node.outerClass);
        }
        collectAnnotationRefs(node.visibleAnnotations, refs);
        collectAnnotationRefs(node.invisibleAnnotations, refs);

        if (node.fields != null) {
            for (FieldNode f : node.fields) {
                addTypeDescriptorRefs(Type.getType(f.desc), refs);
                collectAnnotationRefs(f.visibleAnnotations, refs);
                collectAnnotationRefs(f.invisibleAnnotations, refs);
            }
        }
        if (node.methods != null) {
            for (MethodNode m : node.methods) {
                addMethodTypeRefs(Type.getMethodType(m.desc), refs);
                if (m.exceptions != null) {
                    refs.addAll(m.exceptions);
                }
                collectAnnotationRefs(m.visibleAnnotations, refs);
                collectAnnotationRefs(m.invisibleAnnotations, refs);
                if (m.tryCatchBlocks != null) {
                    for (TryCatchBlockNode tcb : m.tryCatchBlocks) {
                        if (tcb.type != null) {
                            refs.add(tcb.type);
                        }
                    }
                }
                if (m.localVariables != null) {
                    for (LocalVariableNode lv : m.localVariables) {
                        addTypeDescriptorRefs(Type.getType(lv.desc), refs);
                    }
                }
                for (AbstractInsnNode insn : m.instructions) {
                    if (insn instanceof TypeInsnNode tInsn) {
                        if (tInsn.desc.startsWith("[")) {
                            addTypeDescriptorRefs(Type.getType(tInsn.desc), refs);
                        } else {
                            refs.add(tInsn.desc);
                        }
                    } else if (insn instanceof FieldInsnNode fInsn) {
                        refs.add(fInsn.owner);
                        addTypeDescriptorRefs(Type.getType(fInsn.desc), refs);
                    } else if (insn instanceof MethodInsnNode mInsn) {
                        refs.add(mInsn.owner);
                        addMethodTypeRefs(Type.getMethodType(mInsn.desc), refs);
                    } else if (insn instanceof MultiANewArrayInsnNode mArrInsn) {
                        addTypeDescriptorRefs(Type.getType(mArrInsn.desc), refs);
                    } else if (insn instanceof LdcInsnNode ldc) {
                        collectConstantRefs(ldc.cst, refs);
                    } else if (insn instanceof InvokeDynamicInsnNode indy) {
                        addMethodTypeRefs(Type.getMethodType(indy.desc), refs);
                        collectConstantRefs(indy.bsm, refs);
                        if (indy.bsmArgs != null) {
                            for (Object arg : indy.bsmArgs) {
                                collectConstantRefs(arg, refs);
                            }
                        }
                    }
                }
            }
        }
        return refs;
    }

    private static void collectConstantRefs(Object cst, Set<String> refs) {
        if (cst instanceof Type type) {
            if (type.getSort() == Type.METHOD) {
                addMethodTypeRefs(type, refs);
            } else {
                addTypeDescriptorRefs(type, refs);
            }
        } else if (cst instanceof Handle handle) {
            refs.add(handle.getOwner());
            if (handle.getDesc().startsWith("(")) {
                addMethodTypeRefs(Type.getMethodType(handle.getDesc()), refs);
            } else {
                addTypeDescriptorRefs(Type.getType(handle.getDesc()), refs);
            }
        }
    }

    private static void collectAnnotationRefs(List<AnnotationNode> annotations, Set<String> refs) {
        if (annotations == null) {
            return;
        }
        for (AnnotationNode ann : annotations) {
            addTypeDescriptorRefs(Type.getType(ann.desc), refs);
        }
    }

    private static void addMethodTypeRefs(Type methodType, Set<String> refs) {
        addTypeDescriptorRefs(methodType.getReturnType(), refs);
        for (Type arg : methodType.getArgumentTypes()) {
            addTypeDescriptorRefs(arg, refs);
        }
    }

    private static void addTypeDescriptorRefs(Type type, Set<String> refs) {
        if (type == null) {
            return;
        }
        if (type.getSort() == Type.ARRAY) {
            addTypeDescriptorRefs(type.getElementType(), refs);
        } else if (type.getSort() == Type.OBJECT) {
            refs.add(type.getInternalName());
        }
    }

    private static ClassNode readClassNode(String internalName) throws IOException {
        String resourcePath = internalName + ".class";
        byte[] bytes;
        try (InputStream in = ProjectUnitTests.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in != null) {
                bytes = in.readAllBytes();
            } else {
                Path fallback = resolveProjectRoot().resolve("build/classes/java/main/" + resourcePath);
                assertTrue(Files.exists(fallback), "Missing compiled class: " + fallback);
                bytes = Files.readAllBytes(fallback);
            }
        }
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }
}
