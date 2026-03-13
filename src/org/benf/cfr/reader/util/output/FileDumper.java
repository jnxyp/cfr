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
import java.util.concurrent.atomic.AtomicInteger;

public class FileDumper extends StreamDumper {
    private final String dir;
    private final String encoding;
    private final boolean clobber;
    private final JavaTypeInstance type;
    private final SummaryDumper summaryDumper;
    private final String path;
    private final BufferedWriter writer;
    private final AtomicInteger truncCount;
    private final boolean outputStringIndex;
    private final List<StringEntry> stringEntries;

    private static final int MAX_FILE_LEN_MINUS_EXT = 249;
    private static final int TRUNC_PREFIX_LEN = 150;

    private static class StringEntry {
        final long cpIndex;
        final long utf8Index;
        final String rawValue;
        final int line;

        StringEntry(long cpIndex, long utf8Index, String rawValue, int line) {
            this.cpIndex = cpIndex;
            this.utf8Index = utf8Index;
            this.rawValue = rawValue;
            this.line = line;
        }
    }

    private String mkFilename(String dir, Pair<String, String> names, SummaryDumper summaryDumper) {
        String packageName = names.getFirst();
        String className = names.getSecond();
        if (className.length() > MAX_FILE_LEN_MINUS_EXT) {
            /*
             * Have to try to find a replacement name.
             */
            className = className.substring(0, TRUNC_PREFIX_LEN) + "_cfr_" + truncCount.getAndIncrement();
            summaryDumper.notify("Class name " + names.getSecond() + " was shortened to " + className + " due to filesystem limitations.");
        }

        return dir + File.separator + packageName.replace(".", File.separator) +
                ((packageName.length() == 0) ? "" : File.separator) +
                className + ".java";
    }

    FileDumper(String dir, boolean clobber, JavaTypeInstance type, SummaryDumper summaryDumper, TypeUsageInformation typeUsageInformation, Options options, AtomicInteger truncCount, IllegalIdentifierDump illegalIdentifierDump) {
       this(dir,null,clobber,type,summaryDumper,typeUsageInformation,options,truncCount,illegalIdentifierDump);
    }

    FileDumper(String dir, String encoding, boolean clobber, JavaTypeInstance type, SummaryDumper summaryDumper, TypeUsageInformation typeUsageInformation, Options options, AtomicInteger truncCount, IllegalIdentifierDump illegalIdentifierDump) {

        super(typeUsageInformation, options, illegalIdentifierDump, new MovableDumperContext());
        this.truncCount = truncCount;
        this.dir = dir;
        this.encoding = encoding;
        this.clobber = clobber;
        this.type = type;
        this.summaryDumper = summaryDumper;
        this.outputStringIndex = options.getOption(OptionsImpl.OUTPUT_STRING_INDEX);
        this.stringEntries = outputStringIndex ? ListFactory.<StringEntry>newList() : null;
        String fileName = mkFilename(dir, ClassNameUtils.getPackageAndClassNames(type), summaryDumper);
        try {
            File file = new File(fileName);
            File parent = file.getParentFile();
            if (!parent.exists() && !parent.mkdirs()) {
                throw new IllegalStateException("Couldn't create dir: " + parent);
            }
            if (file.exists() && !clobber) {
                throw new CannotCreate("File already exists, and option '" + OptionsImpl.CLOBBER_FILES.getName() + "' not set");
            }
            path = fileName;

            if(encoding != null)
            {
                try {
                    writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file),encoding));
                } catch (UnsupportedEncodingException e) {
                    throw new UnsupportedOperationException("Specified encoding '"+encoding+"' is not supported");
                }
            }else {
                writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file)));
            }
        } catch (FileNotFoundException e) {
            throw new CannotCreate(e);
        }
    }

    @Override
    public void registerStringLiteral(long cpIndex, long utf8Index, String rawValue) {
        if (outputStringIndex) {
            stringEntries.add(new StringEntry(cpIndex, utf8Index, rawValue, getCurrentLine()));
        }
    }

    @Override
    public void close() {
        try {
            writer.close();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        if (outputStringIndex && !stringEntries.isEmpty()) {
            writeStringIndex();
        }
    }

    private void writeStringIndex() {
        // Derive JSON path: strip ".java" suffix, append ".strings.json"
        String jsonPath = path.substring(0, path.length() - 5) + ".strings.json";
        Pair<String, String> names = ClassNameUtils.getPackageAndClassNames(type);
        String packageName = names.getFirst();
        String className = names.getSecond();
        String classPath = packageName.isEmpty() ? className : (packageName.replace(".", "/") + "/" + className);

        OutputStreamWriter sw = null;
        try {
            sw = new OutputStreamWriter(new FileOutputStream(new File(jsonPath)), "UTF-8");
            sw.write("{\n");
            sw.write("  \"class\": \"" + jsonEscape(classPath) + "\",\n");
            sw.write("  \"strings\": [\n");
            for (int i = 0; i < stringEntries.size(); i++) {
                StringEntry e = stringEntries.get(i);
                sw.write("    {\"cp_index\": " + e.cpIndex
                        + ", \"utf8_index\": " + e.utf8Index
                        + ", \"value\": \"" + jsonEscape(e.rawValue) + "\""
                        + ", \"line\": " + e.line + "}");
                if (i < stringEntries.size() - 1) sw.write(",");
                sw.write("\n");
            }
            sw.write("  ]\n");
            sw.write("}\n");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } finally {
            if (sw != null) {
                try { sw.close(); } catch (IOException ignored) {}
            }
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

    @Override
    protected void write(String s) {
        try {
            writer.write(s);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    String getFileName() {
        return path;
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
        String fileName = mkFilename(dir, ClassNameUtils.getPackageAndClassNames(type), summaryDumper);
        fileName = fileName + "." + description;
        try {
            File file = new File(fileName);
            File parent = file.getParentFile();
            if (!parent.exists() && !parent.mkdirs()) {
                throw new IllegalStateException("Couldn't create dir: " + parent);
            }
            if (file.exists() && !clobber) {
                throw new CannotCreate("File already exists, and option '" + OptionsImpl.CLOBBER_FILES.getName() + "' not set");
            }
            return new BufferedOutputStream(new FileOutputStream(file));
        } catch (FileNotFoundException e) {
            throw new CannotCreate(e);
        }

    }
}
