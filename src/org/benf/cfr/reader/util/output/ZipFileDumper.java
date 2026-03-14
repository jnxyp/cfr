package org.benf.cfr.reader.util.output;

import org.benf.cfr.reader.bytecode.analysis.parse.utils.Pair;
import org.benf.cfr.reader.bytecode.analysis.types.ClassNameUtils;
import org.benf.cfr.reader.bytecode.analysis.types.JavaTypeInstance;
import org.benf.cfr.reader.entities.Method;
import org.benf.cfr.reader.state.TypeUsageInformation;
import org.benf.cfr.reader.util.collections.ListFactory;
import org.benf.cfr.reader.util.getopt.Options;
import org.benf.cfr.reader.util.getopt.OptionsImpl;

import java.io.*;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ZipFileDumper extends StreamDumper {
    private final ZipOutputStream zos;
    private final StringWriter buffer;
    private final String entryName;
    private final JavaTypeInstance type;
    private final SummaryDumper summaryDumper;
    private final boolean outputStringIndex;
    private final List<StringEntry> stringEntries;

    private static class StringEntry {
        final long cpIndex;
        final long utf8Index;
        final String rawValue;
        final int line;
        final String constTable;

        StringEntry(long cpIndex, long utf8Index, String rawValue, int line, String constTable) {
            this.cpIndex = cpIndex;
            this.utf8Index = utf8Index;
            this.rawValue = rawValue;
            this.line = line;
            this.constTable = constTable;
        }
    }

    ZipFileDumper(ZipOutputStream zos, String prefix, JavaTypeInstance type, SummaryDumper summaryDumper,
                  TypeUsageInformation typeUsageInformation, Options options,
                  IllegalIdentifierDump illegalIdentifierDump) {
        super(typeUsageInformation, options, illegalIdentifierDump, new MovableDumperContext());
        this.zos = zos;
        this.type = type;
        this.summaryDumper = summaryDumper;
        this.outputStringIndex = options.getOption(OptionsImpl.OUTPUT_STRING_INDEX);
        this.stringEntries = outputStringIndex ? ListFactory.<StringEntry>newList() : null;
        this.buffer = new StringWriter();

        Pair<String, String> names = ClassNameUtils.getPackageAndClassNames(type);
        String packageName = names.getFirst();
        String className = names.getSecond();
        String relativePath = packageName.isEmpty()
                ? className + ".java"
                : packageName.replace('.', '/') + "/" + className + ".java";

        // prefix may be like "/META-INF/versions/11/" — strip leading slash for ZIP entries
        String strippedPrefix = (prefix != null && prefix.startsWith("/")) ? prefix.substring(1) : (prefix != null ? prefix : "");
        this.entryName = strippedPrefix + relativePath;
    }

    @Override
    public void registerStringLiteral(long cpIndex, long utf8Index, String rawValue, String sourceClassRawName) {
        if (outputStringIndex) {
            stringEntries.add(new StringEntry(
                    cpIndex,
                    utf8Index,
                    rawValue,
                    getCurrentLine(),
                    getConstTableSuffix(sourceClassRawName)
            ));
        }
    }

    @Override
    protected void write(String s) {
        buffer.write(s);
    }

    @Override
    public void close() {
        try {
            byte[] javaBytes = buffer.toString().getBytes("UTF-8");
            zos.putNextEntry(new ZipEntry(entryName));
            zos.write(javaBytes);
            zos.closeEntry();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        if (outputStringIndex && stringEntries != null && !stringEntries.isEmpty()) {
            writeStringIndex();
        }
    }

    private void writeStringIndex() {
        Pair<String, String> names = ClassNameUtils.getPackageAndClassNames(type);
        String packageName = names.getFirst();
        String className = names.getSecond();
        String classPath = packageName.isEmpty() ? className : (packageName.replace(".", "/") + "/" + className);
        // derive JSON entry name from java entry name
        String jsonEntry = entryName.substring(0, entryName.length() - 5) + ".strings.json";

        try {
            zos.putNextEntry(new ZipEntry(jsonEntry));
            StringWriter sw = new StringWriter();
            sw.write("{\n");
            sw.write("  \"class\": \"" + jsonEscape(classPath) + "\",\n");
            sw.write("  \"strings\": [\n");
            for (int i = 0; i < stringEntries.size(); i++) {
                StringEntry e = stringEntries.get(i);
                sw.write("    {\"cp_index\": " + e.cpIndex
                        + ", \"utf8_index\": " + e.utf8Index
                        + ", \"const_table\": \"" + jsonEscape(e.constTable) + "\""
                        + ", \"value\": \"" + jsonEscape(e.rawValue) + "\""
                        + ", \"line\": " + e.line + "}");
                if (i < stringEntries.size() - 1) sw.write(",");
                sw.write("\n");
            }
            sw.write("  ]\n");
            sw.write("}\n");
            zos.write(sw.toString().getBytes("UTF-8"));
            zos.closeEntry();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String jsonEscape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    private String getConstTableSuffix(String sourceClassRawName) {
        String ownerRawName = type.getRawName();
        if (sourceClassRawName == null || sourceClassRawName.equals(ownerRawName)) {
            return "";
        }
        if (sourceClassRawName.startsWith(ownerRawName)) {
            return sourceClassRawName.substring(ownerRawName.length());
        }
        return sourceClassRawName;
    }

    @Override
    public void addSummaryError(Method method, String s) {
        summaryDumper.notifyError(type, method, s);
    }

    @Override
    public Dumper withTypeUsageInformation(TypeUsageInformation innerclassTypeUsageInformation) {
        return new TypeOverridingDumper(this, innerclassTypeUsageInformation);
    }

    @Override
    public BufferedOutputStream getAdditionalOutputStream(String description) {
        // ZIP mode does not support additional output streams (e.g. lineNumberTable)
        return new BufferedOutputStream(new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                // discard
            }
        });
    }
}
