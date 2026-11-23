package com.example.xianxia.e2e;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure-JDK JVM classfile (JVMS §4) inspector for headless, non-initializing E2E compliance checks.
 * Avoids Class.forName initialization and requires zero external ASM compile dependencies.
 */
final class E2EClassfileInspector {

    static final int ACC_PUBLIC = 0x0001;
    static final int ACC_STATIC = 0x0008;

    private E2EClassfileInspector() {
    }

    record MemberRef(String owner, String name, String descriptor) {
    }

    record AnnotationInfo(String descriptor, Map<String, Object> values) {
        String getString(String key) {
            Object val = values.get(key);
            return val instanceof String s ? s : null;
        }

        List<String> getEnumValues(String key) {
            Object val = values.get(key);
            if (val instanceof List<?> list) {
                List<String> result = new ArrayList<>();
                for (Object item : list) {
                    if (item instanceof String s) {
                        result.add(s);
                    }
                }
                return result;
            }
            if (val instanceof String s) {
                return List.of(s);
            }
            return List.of();
        }
    }

    record Instruction(int offset, int opcode, MemberRef memberRef) {
        boolean isConditionalBranch() {
            return (opcode >= 0x99 && opcode <= 0xA6) || opcode == 0xC6 || opcode == 0xC7;
        }
    }

    record MethodInfo(
            int accessFlags,
            String name,
            String descriptor,
            List<AnnotationInfo> annotations,
            List<Instruction> instructions,
            int exceptionHandlerCount
    ) {
        boolean isPublic() {
            return (accessFlags & ACC_PUBLIC) != 0;
        }

        boolean isStatic() {
            return (accessFlags & ACC_STATIC) != 0;
        }

        boolean hasAnnotation(String descriptor) {
            return annotations.stream().anyMatch(a -> a.descriptor().equals(descriptor));
        }

        boolean hasExceptionHandlers() {
            return exceptionHandlerCount > 0;
        }
    }

    private record ParsedCode(List<Instruction> instructions, int exceptionHandlerCount) {
    }

    record ClassInfo(
            int majorVersion,
            int accessFlags,
            String internalName,
            String superName,
            List<String> interfaces,
            List<AnnotationInfo> annotations,
            List<MethodInfo> methods,
            Set<String> utf8Constants,
            Set<String> classConstants,
            List<MemberRef> methodRefs,
            List<MemberRef> fieldRefs
    ) {
        String binaryName() {
            return internalName.replace('/', '.');
        }

        String packageName() {
            int idx = internalName.lastIndexOf('/');
            return idx < 0 ? "" : internalName.substring(0, idx).replace('/', '.');
        }

        AnnotationInfo findAnnotation(String descriptor) {
            for (AnnotationInfo ann : annotations) {
                if (ann.descriptor().equals(descriptor)) {
                    return ann;
                }
            }
            return null;
        }

        boolean hasAnnotation(String descriptor) {
            return findAnnotation(descriptor) != null;
        }
    }

    static ClassInfo parse(byte[] bytes) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int magic = in.readInt();
            if (magic != 0xCAFEBABE) {
                throw new IOException("Invalid classfile magic: 0x" + Integer.toHexString(magic));
            }
            in.readUnsignedShort(); // minor_version
            int majorVersion = in.readUnsignedShort();

            int cpCount = in.readUnsignedShort();
            Object[] cp = new Object[cpCount];
            int[] cpTags = new int[cpCount];
            for (int i = 1; i < cpCount; i++) {
                int tag = in.readUnsignedByte();
                cpTags[i] = tag;
                switch (tag) {
                    case 1 -> cp[i] = in.readUTF();
                    case 3 -> cp[i] = in.readInt();
                    case 4 -> cp[i] = in.readFloat();
                    case 5 -> {
                        cp[i] = in.readLong();
                        i++;
                    }
                    case 6 -> {
                        cp[i] = in.readDouble();
                        i++;
                    }
                    case 7, 8, 16, 19, 20 -> cp[i] = in.readUnsignedShort();
                    case 9, 10, 11, 12, 17, 18 -> cp[i] = new int[]{in.readUnsignedShort(), in.readUnsignedShort()};
                    case 15 -> cp[i] = new int[]{in.readUnsignedByte(), in.readUnsignedShort()};
                    default -> throw new IOException("Unsupported constant pool tag: " + tag);
                }
            }

            Set<String> utf8Constants = new LinkedHashSet<>();
            Set<String> classConstants = new LinkedHashSet<>();
            List<MemberRef> methodRefs = new ArrayList<>();
            List<MemberRef> fieldRefs = new ArrayList<>();

            for (int i = 1; i < cpCount; i++) {
                switch (cpTags[i]) {
                    case 1 -> utf8Constants.add((String) cp[i]);
                    case 7 -> classConstants.add(readClassName(cp, i));
                    case 9 -> fieldRefs.add(readMemberRef(cp, (int[]) cp[i]));
                    case 10, 11 -> methodRefs.add(readMemberRef(cp, (int[]) cp[i]));
                    default -> {
                    }
                }
            }

            int accessFlags = in.readUnsignedShort();
            String internalName = readClassName(cp, in.readUnsignedShort());
            int superIdx = in.readUnsignedShort();
            String superName = superIdx == 0 ? null : readClassName(cp, superIdx);

            int interfacesCount = in.readUnsignedShort();
            List<String> interfaces = new ArrayList<>(interfacesCount);
            for (int i = 0; i < interfacesCount; i++) {
                interfaces.add(readClassName(cp, in.readUnsignedShort()));
            }

            int fieldsCount = in.readUnsignedShort();
            for (int i = 0; i < fieldsCount; i++) {
                in.readUnsignedShort(); // access_flags
                in.readUnsignedShort(); // name_index
                in.readUnsignedShort(); // descriptor_index
                skipAttributes(in);
            }

            int methodsCount = in.readUnsignedShort();
            List<MethodInfo> methods = new ArrayList<>(methodsCount);
            for (int i = 0; i < methodsCount; i++) {
                int mAccess = in.readUnsignedShort();
                String mName = readUtf8(cp, in.readUnsignedShort());
                String mDesc = readUtf8(cp, in.readUnsignedShort());
                List<AnnotationInfo> mAnns = new ArrayList<>();
                List<Instruction> instructions = new ArrayList<>();
                int exceptionHandlerCount = 0;
                int attrCount = in.readUnsignedShort();
                for (int a = 0; a < attrCount; a++) {
                    String attrName = readUtf8(cp, in.readUnsignedShort());
                    int attrLen = in.readInt();
                    byte[] attrBytes = in.readNBytes(attrLen);
                    if ("RuntimeVisibleAnnotations".equals(attrName) || "RuntimeInvisibleAnnotations".equals(attrName)) {
                        mAnns.addAll(parseAnnotations(attrBytes, cp));
                    } else if ("Code".equals(attrName)) {
                        ParsedCode parsedCode = parseCodeInstructions(attrBytes, cp, cpTags);
                        instructions.addAll(parsedCode.instructions());
                        exceptionHandlerCount += parsedCode.exceptionHandlerCount();
                    }
                }
                methods.add(new MethodInfo(
                        mAccess,
                        mName,
                        mDesc,
                        Collections.unmodifiableList(mAnns),
                        Collections.unmodifiableList(instructions),
                        exceptionHandlerCount
                ));
            }

            List<AnnotationInfo> classAnns = new ArrayList<>();
            int classAttrCount = in.readUnsignedShort();
            for (int a = 0; a < classAttrCount; a++) {
                String attrName = readUtf8(cp, in.readUnsignedShort());
                int attrLen = in.readInt();
                byte[] attrBytes = in.readNBytes(attrLen);
                if ("RuntimeVisibleAnnotations".equals(attrName) || "RuntimeInvisibleAnnotations".equals(attrName)) {
                    classAnns.addAll(parseAnnotations(attrBytes, cp));
                }
            }

            return new ClassInfo(
                    majorVersion,
                    accessFlags,
                    internalName,
                    superName,
                    Collections.unmodifiableList(interfaces),
                    Collections.unmodifiableList(classAnns),
                    Collections.unmodifiableList(methods),
                    Collections.unmodifiableSet(utf8Constants),
                    Collections.unmodifiableSet(classConstants),
                    Collections.unmodifiableList(methodRefs),
                    Collections.unmodifiableList(fieldRefs)
            );
        }
    }

    private static void skipAttributes(DataInputStream in) throws IOException {
        int attrCount = in.readUnsignedShort();
        for (int a = 0; a < attrCount; a++) {
            in.readUnsignedShort(); // name_index
            int len = in.readInt();
            in.readNBytes(len);
        }
    }

    private static String readUtf8(Object[] cp, int idx) {
        return idx > 0 && idx < cp.length && cp[idx] instanceof String s ? s : "";
    }

    private static String readClassName(Object[] cp, int idx) {
        if (idx <= 0 || idx >= cp.length || !(cp[idx] instanceof Integer nameIdx)) {
            return "";
        }
        return readUtf8(cp, nameIdx);
    }

    private static MemberRef readMemberRef(Object[] cp, int[] pair) {
        String owner = readClassName(cp, pair[0]);
        int ntIdx = pair[1];
        if (ntIdx <= 0 || ntIdx >= cp.length || !(cp[ntIdx] instanceof int[] nt)) {
            return new MemberRef(owner, "", "");
        }
        return new MemberRef(owner, readUtf8(cp, nt[0]), readUtf8(cp, nt[1]));
    }

    private static List<AnnotationInfo> parseAnnotations(byte[] bytes, Object[] cp) throws IOException {
        List<AnnotationInfo> out = new ArrayList<>();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int numAnnotations = in.readUnsignedShort();
            for (int i = 0; i < numAnnotations; i++) {
                out.add(readAnnotation(in, cp));
            }
        }
        return out;
    }

    private static AnnotationInfo readAnnotation(DataInputStream in, Object[] cp) throws IOException {
        String descriptor = readUtf8(cp, in.readUnsignedShort());
        int numPairs = in.readUnsignedShort();
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < numPairs; i++) {
            String key = readUtf8(cp, in.readUnsignedShort());
            values.put(key, readElementValue(in, cp));
        }
        return new AnnotationInfo(descriptor, Collections.unmodifiableMap(values));
    }

    private static Object readElementValue(DataInputStream in, Object[] cp) throws IOException {
        int tag = in.readUnsignedByte();
        return switch (tag) {
            case 'B', 'C', 'D', 'F', 'I', 'J', 'S', 'Z' -> {
                int idx = in.readUnsignedShort();
                yield cp[idx];
            }
            case 's' -> readUtf8(cp, in.readUnsignedShort());
            case 'e' -> {
                String type = readUtf8(cp, in.readUnsignedShort());
                String constName = readUtf8(cp, in.readUnsignedShort());
                yield type + "#" + constName;
            }
            case 'c' -> readUtf8(cp, in.readUnsignedShort());
            case '@' -> readAnnotation(in, cp);
            case '[' -> {
                int count = in.readUnsignedShort();
                List<Object> arr = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    arr.add(readElementValue(in, cp));
                }
                yield Collections.unmodifiableList(arr);
            }
            default -> throw new IOException("Unsupported annotation element tag: " + (char) tag);
        };
    }

    private static ParsedCode parseCodeInstructions(byte[] attrBytes, Object[] cp, int[] cpTags) throws IOException {
        List<Instruction> list = new ArrayList<>();
        int exceptionTableLength;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(attrBytes))) {
            in.readUnsignedShort(); // max_stack
            in.readUnsignedShort(); // max_locals
            int codeLength = in.readInt();
            byte[] code = in.readNBytes(codeLength);
            exceptionTableLength = in.readUnsignedShort();

            int pc = 0;
            while (pc < code.length) {
                int offset = pc;
                int opcode = code[pc] & 0xFF;
                MemberRef ref = null;
                int step;
                switch (opcode) {
                    case 0x10, 0x12, 0x15, 0x16, 0x17, 0x18, 0x19,
                         0x36, 0x37, 0x38, 0x39, 0x3A, 0xA9, 0xBC -> step = 2;
                    case 0xB2, 0xB3, 0xB4, 0xB5, 0xB6, 0xB7, 0xB8 -> {
                        int cpIdx = ((code[pc + 1] & 0xFF) << 8) | (code[pc + 2] & 0xFF);
                        if (cpIdx > 0 && cpIdx < cp.length && cp[cpIdx] instanceof int[] pair
                                && (cpTags[cpIdx] == 9 || cpTags[cpIdx] == 10 || cpTags[cpIdx] == 11)) {
                            ref = readMemberRef(cp, pair);
                        }
                        step = 3;
                    }
                    case 0x11, 0x13, 0x14, 0x84,
                         0x99, 0x9A, 0x9B, 0x9C, 0x9D, 0x9E, 0x9F, 0xA0,
                         0xA1, 0xA2, 0xA3, 0xA4, 0xA5, 0xA6, 0xA7, 0xA8,
                         0xBB, 0xBD, 0xC0, 0xC1, 0xC6, 0xC7 -> step = 3;
                    case 0xC5 -> step = 4;
                    case 0xB9 -> {
                        int cpIdx = ((code[pc + 1] & 0xFF) << 8) | (code[pc + 2] & 0xFF);
                        if (cpIdx > 0 && cpIdx < cp.length && cp[cpIdx] instanceof int[] pair && cpTags[cpIdx] == 11) {
                            ref = readMemberRef(cp, pair);
                        }
                        step = 5;
                    }
                    case 0xBA, 0xC8, 0xC9 -> step = 5;
                    case 0xAA -> { // tableswitch
                        int pad = (4 - ((pc + 1) % 4)) % 4;
                        int base = pc + 1 + pad;
                        int low = readInt(code, base + 4);
                        int high = readInt(code, base + 8);
                        int count = Math.max(0, high - low + 1);
                        step = (base + 12 + count * 4) - pc;
                    }
                    case 0xAB -> { // lookupswitch
                        int pad = (4 - ((pc + 1) % 4)) % 4;
                        int base = pc + 1 + pad;
                        int npairs = Math.max(0, readInt(code, base + 4));
                        step = (base + 8 + npairs * 8) - pc;
                    }
                    case 0xC4 -> { // wide
                        int wideOp = code[pc + 1] & 0xFF;
                        step = (wideOp == 0x84) ? 6 : 4;
                    }
                    default -> step = 1;
                }
                list.add(new Instruction(offset, opcode, ref));
                pc += step;
            }
        }
        return new ParsedCode(list, exceptionTableLength);
    }

    private static int readInt(byte[] b, int idx) {
        return ((b[idx] & 0xFF) << 24)
                | ((b[idx + 1] & 0xFF) << 16)
                | ((b[idx + 2] & 0xFF) << 8)
                | (b[idx + 3] & 0xFF);
    }
}
